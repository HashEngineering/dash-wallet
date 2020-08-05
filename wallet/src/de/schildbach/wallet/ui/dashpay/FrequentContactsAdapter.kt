/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.ui.dashpay

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.data.OnContactItemClickListener
import de.schildbach.wallet.data.UsernameSearchResult
import org.dashevo.dpp.util.HashUtils
import java.math.BigInteger


class FrequentContactsAdapter() :
        RecyclerView.Adapter<FrequentContactViewHolder>() {

    init {
        setHasStableIds(true)
    }

    var itemClickListener: OnContactItemClickListener? = null
    var results: ArrayList<UsernameSearchResult> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FrequentContactViewHolder {
         return FrequentContactViewHolder(LayoutInflater.from(parent.context), parent, itemClickListener)
    }

    override fun getItemCount(): Int {
        return results.size
    }

    private fun getLongValue(s: String): Long {
        val byteArray = HashUtils.byteArrayFromString(s)
        val bigInteger = BigInteger(byteArray)
        return bigInteger.toLong()
    }

    override fun getItemId(position: Int): Long {
        return getLongValue(results[position].fromContactRequest!!.userId)
    }

    override fun onBindViewHolder(holder: FrequentContactViewHolder, position: Int) {
        holder.bind(results[position])
    }

    /*override fun onBindViewHolder(holder: FrequentContactViewHolder, position: Int, payloads: List<Any?>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.itemView.alpha = 1f
        }
    }*/
}