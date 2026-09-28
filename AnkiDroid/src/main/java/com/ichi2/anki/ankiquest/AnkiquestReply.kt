// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ichi2.anki.R
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Contextual notification replies, or your own words. */
object AnkiquestReply {
    enum class Outcome { SENT, RETRY, FAILED }

    const val NOTIFICATION_KEY = "notification"
    const val TAG_KEY = "tag"
    const val TITLE_KEY = "title"
    const val BODY_KEY = "body"
    const val KIND_KEY = "kind"
    const val MESSAGE_KEY = "message"
    const val ACCOUNT_KEY = "ankiquest.reply_account"
    const val SCOPE_KEY = "ankiquest.reply_scope"
    const val CUSTOM_ACTION = "com.ichi2.anki.ankiquest.REPLY_CUSTOM"

    const val ATTEMPTS = 3
    const val MAX_MESSAGE = 200

    private const val WORK_NAME = "ankiquestReply"

    /** One Reply action: its first suggestion is the one-tap answer, and free text is always allowed. */
    fun actions(
        context: Context,
        notification: Long,
        tag: Int,
        title: String,
        body: String,
        account: String?,
        scope: String?,
        kind: String = "completion",
    ): List<NotificationCompat.Action> {
        val current = AnkiquestHomeData.account() ?: return emptyList()
        if (scope.isNullOrEmpty() || current.scope != scope || current.notificationAccount != account) return emptyList()
        val quick =
            when (kind) {
                "completion" -> AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_cheer)
                "reply" -> AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_thanks)
                "nudge" -> AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_on_it)
                else -> null
            }
        val suggestions =
            when (kind) {
                "completion" -> AnkiquestLanguage.context(context).resources.getStringArray(R.array.ankiquest_reply_choices)
                "reply" -> AnkiquestLanguage.context(context).resources.getStringArray(R.array.ankiquest_reply_thanks_choices)
                "nudge" -> AnkiquestLanguage.context(context).resources.getStringArray(R.array.ankiquest_reply_nudge_choices)
                else -> emptyArray()
            }
        val choices = listOfNotNull(quick).toTypedArray<CharSequence>() + suggestions
        val intent =
            Intent(context, AnkiquestReplyReceiver::class.java)
                .setAction(CUSTOM_ACTION)
                .putExtra(NOTIFICATION_KEY, notification)
                .putExtra(TAG_KEY, tag)
                .putExtra(TITLE_KEY, title)
                .putExtra(BODY_KEY, body)
                .putExtra(KIND_KEY, kind)
                .putExtra(ACCOUNT_KEY, account)
                .putExtra(SCOPE_KEY, scope)
        val reply =
            RemoteInput
                .Builder(MESSAGE_KEY)
                .setLabel(AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply_hint))
                .setChoices(choices)
                .build()
        return listOf(
            NotificationCompat.Action
                .Builder(
                    R.drawable.ic_star_notify,
                    AnkiquestLanguage.context(context).getString(R.string.ankiquest_reply),
                    PendingIntent.getBroadcast(
                        context,
                        tag,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    ),
                ).addRemoteInput(reply)
                .setAllowGeneratedReplies(false)
                .build(),
        )
    }

    fun send(
        context: Context,
        data: Data,
    ) {
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME:${data.getString(SCOPE_KEY)}:${data.getInt(TAG_KEY, 0)}",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AnkiquestReplyWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(data)
                    .build(),
            )
        } catch (e: Exception) {
            Timber.w(e, "ankiquest could not queue a reply")
        }
    }

    /** The chosen or typed text; notifications posted by older versions carry a cheer as an extra instead. */
    fun message(intent: Intent): String =
        (
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MESSAGE_KEY)?.toString()
                ?: intent.getStringExtra(MESSAGE_KEY)
        ).orEmpty()
            .trim()
            .take(MAX_MESSAGE)

    fun data(
        intent: Intent,
        message: String,
    ): Data =
        Data
            .Builder()
            .putLong(NOTIFICATION_KEY, intent.getLongExtra(NOTIFICATION_KEY, 0))
            .putInt(TAG_KEY, intent.getIntExtra(TAG_KEY, 0))
            .putString(TITLE_KEY, intent.getStringExtra(TITLE_KEY))
            .putString(BODY_KEY, intent.getStringExtra(BODY_KEY))
            .putString(KIND_KEY, intent.getStringExtra(KIND_KEY) ?: "completion")
            .putString(MESSAGE_KEY, message)
            .putString(ACCOUNT_KEY, intent.getStringExtra(ACCOUNT_KEY))
            .putString(SCOPE_KEY, intent.getStringExtra(SCOPE_KEY))
            .build()

    suspend fun run(
        context: Context,
        data: Data,
        attempt: Int,
    ): Outcome {
        val message = data.getString(MESSAGE_KEY).orEmpty()
        val notification = data.getLong(NOTIFICATION_KEY, 0)
        val account = data.getString(ACCOUNT_KEY).orEmpty()
        val scope = data.getString(SCOPE_KEY).orEmpty()
        if (message.isEmpty() || notification <= 0 || account.isEmpty() || scope.isEmpty() || !current(data)) return Outcome.FAILED
        return try {
            AnkiquestNotifier.onReplySent(context, data, Ankiquest.reply(notification, message, account, scope))
            Outcome.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never republish the old account's private notification after a switch.
            if (!current(data)) return Outcome.FAILED
            Timber.w(e, "ankiquest reply failed")
            // A refused reply stays refused; only a broken connection is worth another try.
            val done = e is Ankiquest.Rejected || attempt + 1 >= ATTEMPTS
            if (done) AnkiquestNotifier.onReplyFailed(context, data)
            if (done) Outcome.FAILED else Outcome.RETRY
        }
    }

    internal fun current(data: Data): Boolean {
        val account = AnkiquestHomeData.account() ?: return false
        return account.token.isNotEmpty() && account.notificationAccount == data.getString(ACCOUNT_KEY) &&
            account.scope == data.getString(SCOPE_KEY)
    }
}

class AnkiquestReplyReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val message = AnkiquestReply.message(intent)
        if (message.isEmpty()) return
        AnkiquestReply.send(context, AnkiquestReply.data(intent, message))
    }
}

class AnkiquestReplyWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (AnkiquestReply.run(applicationContext, inputData, runAttemptCount)) {
            AnkiquestReply.Outcome.SENT -> Result.success()
            AnkiquestReply.Outcome.RETRY -> Result.retry()
            AnkiquestReply.Outcome.FAILED -> Result.failure()
        }
}
