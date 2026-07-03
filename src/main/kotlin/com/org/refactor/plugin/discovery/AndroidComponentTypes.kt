package com.org.refactor.plugin.discovery

object AndroidComponentTypes {

    val ACTIVITY_SUPERCLASSES: Set<String> = setOf(
        "android.app.Activity",
        "androidx.activity.ComponentActivity",
        "androidx.appcompat.app.AppCompatActivity",
        "androidx.fragment.app.FragmentActivity",
    )

    val FRAGMENT_SUPERCLASSES: Set<String> = setOf(
        "android.app.Fragment",
        "androidx.fragment.app.Fragment",
    )

    val DIALOG_SUPERCLASSES: Set<String> = setOf(
        "android.app.Dialog",
        "androidx.appcompat.app.AppCompatDialog",
    )

    val DIALOG_FRAGMENT_SUPERCLASSES: Set<String> = setOf(
        "androidx.fragment.app.DialogFragment",
    )

    val BOTTOM_SHEET_DIALOG_FRAGMENT_SUPERCLASSES: Set<String> = setOf(
        "com.google.android.material.bottomsheet.BottomSheetDialogFragment",
    )

    val EXCLUDED_SUPERCLASSES: Set<String> = setOf(
        "androidx.lifecycle.ViewModel",
        "androidx.lifecycle.AndroidViewModel",
        "androidx.room.RoomDatabase",
        "androidx.work.Worker",
        "androidx.work.ListenableWorker",
        "androidx.work.CoroutineWorker",
        "android.app.Service",
        "androidx.recyclerview.widget.RecyclerView.Adapter",
    )

    fun isExcludedSuperclass(fqn: String): Boolean {
        return EXCLUDED_SUPERCLASSES.any { fqn == it || fqn.startsWith("$it<") }
    }
}
