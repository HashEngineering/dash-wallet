/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.schildbach.wallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.bitcoinj.core.Sha256Hash

@Dao
interface InvitationsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(invitation: Invitation)

    @Query("SELECT * FROM invitation_table WHERE userId = :userId")
    suspend fun loadByUserId(userId: String): Invitation?

    @Query("SELECT * FROM invitation_table WHERE txid = :txid")
    suspend fun loadByUsername(txid: Sha256Hash): Invitation?

    @Query("SELECT * FROM invitation_table")
    suspend fun loadAll(): List<Invitation>

    @Query("DELETE FROM invitation_table")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM invitation_table")
    suspend fun count(): Int
}