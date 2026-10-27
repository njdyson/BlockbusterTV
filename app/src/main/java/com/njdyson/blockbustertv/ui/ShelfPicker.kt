package com.njdyson.blockbustertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.njdyson.blockbustertv.data.Shelf

/** A list of choices in a dialog, D-pad friendly. Used for "Add to which shelf?" and the box menu. */
@Composable
fun ChoiceDialog(title: String, choices: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(420.dp).background(ShelfGrey, RoundedCornerShape(12.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = TicketYellow, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(choices.size) { i ->
                    val (label, action) = choices[i]
                    Button(
                        // Action first: "New shelf…" switches the picker to naming mode, and
                        // onDismiss then sees that and keeps the picker open.
                        onClick = { action(); onDismiss() },
                        modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it },
                    ) { Text(label) }
                }
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }
}

/** Pick a shelf, or make a new one (opens the on-screen keyboard). */
@Composable
fun ShelfPicker(shelves: List<Shelf>, onPick: (shelfId: String?, newName: String?) -> Unit, onDismiss: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        TextEntryDialog("Name the new shelf", onDone = { name -> onPick(null, name) }, onDismiss = onDismiss)
    } else {
        ChoiceDialog(
            "Add to which shelf?",
            shelves.map { shelf -> shelf.name to { onPick(shelf.id, null) } } + ("+ New shelf…" to { naming = true }),
            onDismiss = { if (!naming) onDismiss() },
        )
    }
}

/** Text entry with an explicit confirm button: the Fire TV keyboard doesn't always offer Done. */
@Composable
fun TextEntryDialog(title: String, initial: String = "", confirmLabel: String = "Create shelf", onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val submit = { if (text.isNotBlank()) onDone(text.trim()) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(480.dp).background(ShelfGrey, RoundedCornerShape(12.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = TicketYellow, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TvTextField(initial = initial, placeholder = "Type a name", onValueChange = { text = it }, onSubmit = { submit() })
            Text("Press Select to type, then Back to close the keyboard.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            Button(onClick = submit, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(confirmLabel) }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
