package com.supercilex.robotscouter.util.data.model

import com.google.android.gms.tasks.Continuation
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.QuerySnapshot
import com.supercilex.robotscouter.data.model.Scout
import com.supercilex.robotscouter.data.model.Team
import com.supercilex.robotscouter.data.model.isNativeTemplateType
import com.supercilex.robotscouter.util.AsyncTaskExecutor
import com.supercilex.robotscouter.util.CrashLogger
import com.supercilex.robotscouter.util.FIRESTORE_DEFAULT_TEMPLATES
import com.supercilex.robotscouter.util.FIRESTORE_METRICS
import com.supercilex.robotscouter.util.FIRESTORE_NAME
import com.supercilex.robotscouter.util.FIRESTORE_SCOUTS
import com.supercilex.robotscouter.util.data.SCOUT_PARSER
import com.supercilex.robotscouter.util.data.delete
import com.supercilex.robotscouter.util.data.firestoreBatch
import com.supercilex.robotscouter.util.logAddScoutEvent

fun Team.getScoutRef() = ref.collection(FIRESTORE_SCOUTS)

fun Team.getScoutMetricsRef(id: String) = getScoutRef().document(id).collection(FIRESTORE_METRICS)

fun Team.addScout(overrideId: String? = null): String {
    val templateId = overrideId ?: templateId

    val scoutRef = getScoutRef().document()
    scoutRef.set(Scout(scoutRef.id, templateId))

    val nameCollector = TaskCompletionSource<String?>()
    if (isNativeTemplateType(templateId)) {
        FIRESTORE_DEFAULT_TEMPLATES.get().continueWithTask(
                AsyncTaskExecutor, Continuation<QuerySnapshot, Task<Void>> {
            val scout = it.result.map { SCOUT_PARSER.parseSnapshot(it) }
                    .find { it.id == templateId }!!
            nameCollector.setResult(scout.name)

            firestoreBatch {
                scout.metrics.forEach {
                    set(getScoutMetricsRef(scoutRef.id).document(it.ref.id), it)
                }
            }
        }).addOnFailureListener(CrashLogger).addOnFailureListener {
            nameCollector.setException(it)
        }
    } else {
        getTemplateRef(templateId).get().addOnCompleteListener {
            if (it.isSuccessful) nameCollector.setResult(SCOUT_PARSER.parseSnapshot(it.result).name)
            else nameCollector.setException(it.exception!!)
        }.addOnFailureListener(CrashLogger)

        getTemplateRef(templateId).collection(FIRESTORE_METRICS).get().continueWithTask(
                AsyncTaskExecutor, Continuation<QuerySnapshot, Task<Void>> {
            firestoreBatch {
                it.result.documents.associate { it.id to it.data }.forEach {
                    set(getScoutMetricsRef(scoutRef.id).document(it.key), it.value)
                }
            }
        }).addOnFailureListener(CrashLogger)
    }

    nameCollector.task.continueWithTask {
        it.result!!
        getScoutRef().get()
    }.continueWith(AsyncTaskExecutor, Continuation<QuerySnapshot, Nothing?> {
        val nExistingTemplates = it.result.map {
            SCOUT_PARSER.parseSnapshot(it).templateId
        }.groupBy { it }[templateId]!!.size

        scoutRef.update(FIRESTORE_NAME, "${nameCollector.task.result} $nExistingTemplates")
        null
    })

    logAddScoutEvent(this, id, templateId)
    return scoutRef.id
}

fun Team.deleteScout(id: String): Task<Void> = getScoutMetricsRef(id).delete().continueWithTask {
    getScoutRef().document(id).delete()
}

fun Team.deleteAllScouts(): Task<Void> = getScoutRef().get().continueWithTask(
        AsyncTaskExecutor, Continuation<QuerySnapshot, Task<Void>> {
    Tasks.whenAll(it.result.map { deleteScout(it.id) })
})
