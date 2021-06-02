/*
 * Copyright 2015-present the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.dash.wallet.integration.liquid.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import org.dash.wallet.integration.liquid.data.LiquidClient
import org.dash.wallet.integration.liquid.data.LiquidConstants
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.customtabs.CustomTabActivityHelper
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.liquid.R
import org.slf4j.LoggerFactory


class LiquidSplashActivity : InteractionAwareActivity() {

    private var loadingDialog: ProgressDialog? = null
    private var liquidClient: LiquidClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.liquid_splash_screen)
        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        liquidClient = LiquidClient.getInstance()
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setDisplayShowHomeEnabled(true)
        }
        actionBar?.setTitle(R.string.link_liquid_account)
        loadingDialog = ProgressDialog(this)
        loadingDialog!!.isIndeterminate = true
        loadingDialog!!.setCancelable(false)
        loadingDialog!!.setMessage(getString(R.string.loading))
        findViewById<View>(R.id.liquid_link_account).setOnClickListener { authUser() }

        handleIntent(intent)
    }


    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Check deeplinking to get user logged in or not
     */

    private fun handleIntent(intent: Intent?) {
        val action = intent!!.action
        val intentUri = intent.data
        val scheme = intentUri?.scheme
        val host = intentUri?.host

        if (Intent.ACTION_VIEW == action
                && scheme == LiquidConstants.OAUTH_CALLBACK_SCHEMA
                && host == LiquidConstants.OAUTH_CALLBACK_HOST) {
            getUserId()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left)
    }

    override fun onDestroy() {
        loadingDialog?.dismiss()
        super.onDestroy()
    }

    /**
     * call api for get session id
     */
    private fun authUser() {
        if (GenericUtils.isInternetConnected(this)) {
            if (liquidClient!!.storedSessionId!!.isEmpty()) {
                loadingDialog!!.show()
                liquidClient?.getSessionId(LiquidConstants.PUBLIC_API_KEY, object : LiquidClient.Callback<String> {

                    override fun onSuccess(data: String) {
                        log.info("liquid: get session id successful")
                        if (isFinishing) {
                            return
                        }
                        loadingDialog!!.hide()
                        openLoginUrl(data)
                    }

                    override fun onError(e: Exception?) {
                        log.error("liquid: cannot obtain session id: ${e?.message}")
                        if (isFinishing) {
                            return
                        }
                        loadingDialog!!.hide()
                        showLoadingErrorAlert()
                    }
                })
            } else {
                openLoginUrl(liquidClient?.storedSessionId!!)
            }
        } else {
            GenericUtils.showToast(this, getString(R.string.internet_connected))
        }
    }

    /**
     * Call api to get user liquid details
     */
    private fun getUserId() {
        log.info("liquid: requesting user details")
        if (GenericUtils.isInternetConnected(this)) {
            loadingDialog!!.show()
            liquidClient?.getUserKycState(liquidClient?.storedSessionId!!, object : LiquidClient.Callback<String> {

                override fun onSuccess(data: String) {
                    log.info("liquid: get user id successful")
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                    startLiquidBuyAndSellDashActivity()
                }

                override fun onError(e: Exception?) {
                    log.error("liquid: cannot obtain user id: ${e?.message}")
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                    showLoadingErrorAlert()
                    liquidClient?.clearStoredSessionData()
                }
            })
        } else {
            GenericUtils.showToast(this, getString(R.string.internet_connected))
        }
    }

    private fun showLoadingErrorAlert() {
        val builder = AlertDialog.Builder(this)
        builder.setCancelable(false)
        builder.setMessage(R.string.loading_error)
        builder.setPositiveButton(android.R.string.ok, null)
        val dialog: Dialog = builder.show()
        dialog.setOnDismissListener { finish() }
    }

    private fun startLiquidBuyAndSellDashActivity() {

        setResult(RESULT_OK)
        val intent = Intent(this, LiquidBuyAndSellDashActivity::class.java)
        val extras = getIntent().extras
        if (extras != null) {
            intent.putExtras(extras)
        }
        startActivity(intent)
        finish()
    }

    /**
     * Open login url in webview
     */
    private fun openLoginUrl(sessionId: String) {
        log.info("liquid: opening webview to log in")
        val url = "${LiquidConstants.INITIAL_URL}${sessionId}/liquid_oauth?preferred_action=follow_href&theme=light&return_url=${LiquidConstants.OAUTH_CALLBACK_URL}"

        val builder = CustomTabsIntent.Builder()
        val toolbarColor = ContextCompat.getColor(this, R.color.colorPrimary)
        val colorSchemeParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(toolbarColor)
                .build()
        val customTabsIntent = builder.setShowTitle(true)
                .setDefaultColorSchemeParams(colorSchemeParams)
                .build()

        val uri = Uri.parse(url)

        CustomTabActivityHelper.openCustomTab(this, customTabsIntent, uri
        ) { _, _ ->
            log.info("liquid: login successful")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = uri
            startActivity(intent)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val LOGIN_REQUEST_CODE = 102
        val log = LoggerFactory.getLogger(LiquidSplashActivity::class.java)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LOGIN_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            getUserId()
        }
    }
}