package com.davidferrand.coroutinessample

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*

@Entity(tableName = "data")
data class Data(
    @ColumnInfo(name = "id") @PrimaryKey val id: Int,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long
) {
    fun isFresh() = Date(expiresAtMs).after(Date())

    override fun toString(): String = """Data(
    id=$id,
    expiresAt=${expiresAtMs.formatAsTime()}"""
}