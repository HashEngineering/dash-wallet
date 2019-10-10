package de.schildbach.wallet.ui

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.core.os.CancellationSignal
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.preference.PinRetryController
import de.schildbach.wallet.ui.widget.NumericKeyboardView
import de.schildbach.wallet.util.FingerprintHelper
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_enter_pin.*


class CheckPinDialog : DialogFragment() {

    companion object {
        private val FRAGMENT_TAG = CheckPinDialog::class.java.canonicalName
        private const val ARG_REQUEST_CODE = "arg_request_code"

        @JvmStatic
        fun show(manager: FragmentManager, requestCode: Int) {
            val checkPinDialog = CheckPinDialog()
            val args = Bundle()
            args.putInt(ARG_REQUEST_CODE, requestCode)
            checkPinDialog.arguments = args
            checkPinDialog.show(manager, FRAGMENT_TAG)
        }
    }

    private var state = State.ENTER_PIN

    private lateinit var viewModel: CheckPinViewModel
    private lateinit var sharedModel: CheckPinSharedModel

    private lateinit var pinRetryController: PinRetryController
    private var fingerprintHelper: FingerprintHelper? = null
    private lateinit var fingerprintCancellationSignal: CancellationSignal

    private enum class State {
        ENTER_PIN,
        DECRYPTING
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_enter_pin, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProviders.of(this).get(CheckPinViewModel::class.java)
        initFingerprint()

        viewModel.encryptWalletLiveData.observe(viewLifecycleOwner, Observer {
            when (it.status) {
                Status.ERROR -> {
                    setState(State.ENTER_PIN)
                    pin_preview.shake()
                }
                Status.LOADING -> {
                    setState(State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    val requestCode = arguments!!.getInt(ARG_REQUEST_CODE)
                    sharedModel.onCorrectPinCallback.value = Pair(requestCode, viewModel.pin.toString())
                    dismiss()
                }
            }
        })

        cancel_button.setOnClickListener {
            sharedModel.onCancelCallback.call()
            dismiss()
        }
        pin_or_fingerprint_button.setOnClickListener {
            if (pin_preview.visibility == View.VISIBLE) {
                fingerprintFlow(true)
            } else {
                fingerprintFlow(false)
            }
        }
        numeric_keyboard.setFunctionEnabled(false)
        numeric_keyboard.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

            override fun onNumber(number: Int) {
                if (viewModel.pin.length < 4) {
                    viewModel.pin.append(number)
                    pin_preview.next()
                }
                if (viewModel.pin.length == 4) {
                    Handler().postDelayed({
                        viewModel.checkPin(viewModel.pin)
                    }, 200)
                }
            }

            override fun onBack(longClick: Boolean) {
                if (viewModel.pin.isNotEmpty()) {
                    viewModel.pin.deleteCharAt(viewModel.pin.length - 1)
                    pin_preview.prev()
                }
            }

            override fun onFunction() {

            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activity?.run {
            pinRetryController = PinRetryController(this)
            sharedModel = ViewModelProviders.of(this)[CheckPinSharedModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }

    private fun setState(newState: State) {
        when (newState) {
            State.ENTER_PIN -> {
                if (pin_progress_switcher.currentView.id == R.id.progress) {
                    pin_progress_switcher.showPrevious()
                }
                viewModel.pin.clear()
                Handler().postDelayed({
                    pin_preview.clear()
                }, 200)
            }
            State.DECRYPTING -> {
                if (pin_progress_switcher.currentView.id != R.id.progress) {
                    pin_progress_switcher.showNext()
                }
            }
        }
        state = newState
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (::fingerprintCancellationSignal.isInitialized) {
            fingerprintCancellationSignal.cancel()
        }
        super.onDismiss(dialog)
    }

    private fun initFingerprint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fingerprintHelper = FingerprintHelper(activity)
            fingerprintHelper?.run {
                if (init()) {
                    if (isFingerprintEnabled) {
                        fingerprintFlow(true)
                        startFingerprintListener()
                    } else {
                        fingerprint_view.setText(R.string.touch_fingerprint_to_enable)
                    }
                } else {
                    fingerprintHelper = null
                    fingerprintFlow(false)
                }
            }
        }
    }

    private fun fingerprintFlow(active: Boolean) {
        fingerprint_view.visibility = if (active) View.VISIBLE else View.GONE
        pin_preview.visibility = if (active) View.GONE else View.VISIBLE
        numeric_keyboard.visibility = if (active) View.GONE else View.VISIBLE
        message.setText(if (active) R.string.authenticate_fingerprint_message else R.string.authenticate_pin_message)
        pin_or_fingerprint_button.setText(if (active) R.string.authenticate_switch_to_pin else R.string.authenticate_switch_to_fingerprint)
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun startFingerprintListener() {
        fingerprintCancellationSignal = CancellationSignal()
        fingerprintHelper!!.getPassword(fingerprintCancellationSignal, object : FingerprintHelper.Callback {
            override fun onSuccess(savedPass: String) {
                viewModel.checkPin(savedPass)
            }

            override fun onFailure(message: String, canceled: Boolean, exceededMaxAttempts: Boolean) {
                if (!canceled) {
                    fingerprint_view.showError(exceededMaxAttempts)
                }
            }

            override fun onHelp(helpCode: Int, helpString: String) {
                fingerprint_view.showError(false)
            }
        })
    }
}
