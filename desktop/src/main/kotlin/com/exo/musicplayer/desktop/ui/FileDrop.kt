package com.exo.musicplayer.desktop.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * Takes what is dragged onto the window from outside Resonate: files and
 * folders from Explorer, or text such as a link dragged out of a browser.
 *
 * Files are taken first when both are offered. [onText] says whether it could
 * use the text, and Windows is told whether the drop was accepted accordingly.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun fileDropTarget(
    onHover: (Boolean) -> Unit,
    onFiles: (List<File>) -> Unit,
    onText: (String) -> Boolean
): DragAndDropTarget = object : DragAndDropTarget {
    override fun onEntered(event: DragAndDropEvent) = onHover(true)
    override fun onExited(event: DragAndDropEvent) = onHover(false)
    override fun onEnded(event: DragAndDropEvent) = onHover(false)

    override fun onDrop(event: DragAndDropEvent): Boolean {
        onHover(false)
        val transferable = event.awtTransferable
        val files = runCatching {
            (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
                .filterIsInstance<File>()
        }.getOrNull()
        if (!files.isNullOrEmpty()) {
            onFiles(files)
            return true
        }
        val text = runCatching {
            transferable.getTransferData(DataFlavor.stringFlavor) as String
        }.getOrNull()
        return text != null && onText(text)
    }
}
