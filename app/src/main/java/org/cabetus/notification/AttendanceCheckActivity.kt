package org.cabetus.notification

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import org.cabetus.data.local.LocalAttendanceEntity
import org.cabetus.data.local.LocalAttendanceDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 授業開始通知の「出席チェック」ボタンから起動される透明Activity。
 * ローカル出席を記録し、既定ブラウザで CLASS を開く。
 * （通知トランポリン規制のため Activity を直接ターゲットにする）
 */
@AndroidEntryPoint
class AttendanceCheckActivity : ComponentActivity() {

    @Inject
    lateinit var localAttendanceDao: LocalAttendanceDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val courseCode = intent.getStringExtra(EXTRA_COURSE_CODE)
        val date = intent.getLongExtra(EXTRA_DATE, 0L)
        val period = intent.getIntExtra(EXTRA_PERIOD, 0)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        if (notificationId != -1) {
            NotificationManagerCompat.from(this).cancel(notificationId)
        }

        if (courseCode != null && date != 0L) {
            val entity = LocalAttendanceEntity(
                courseCode = courseCode,
                date = date,
                period = period,
                checkedAt = System.currentTimeMillis(),
            )
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { localAttendanceDao.insert(entity) }
            }
        }

        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(CLASS_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_COURSE_CODE = "course_code"
        const val EXTRA_DATE = "date"
        const val EXTRA_PERIOD = "period"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val CLASS_URL = "https://class.admin.tus.ac.jp/"
    }
}
