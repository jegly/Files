package com.jegly.files.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jegly.files.model.FileKind
import com.jegly.files.model.SortBy
import com.jegly.files.model.SortSpec
import com.jegly.files.ops.ConflictPolicy
import com.jegly.files.ops.OpKind
import com.jegly.files.vm.PastePrompt
import com.jegly.files.vm.Properties

/**
 * Name entry for "new folder" and "rename".
 *
 * Validation lives here as well as in FileRepository.sanitizedName on purpose: the repository
 * rejects, but only after the user has committed. Doing the same checks live means a bad name is
 * unenterable rather than a failure toast after the fact, and the two agree on what's bad.
 */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val problem = nameProblem(name)

    // Open with the keyboard already up — this dialog exists solely to accept typing.
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = name.isNotEmpty() && problem != null,
                    supportingText = problem?.takeIf { name.isNotEmpty() }?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = problem == null,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Null when the name is usable. Mirrors FileRepository.sanitizedName's rules. */
private fun nameProblem(raw: String): String? {
    val name = raw.trim()
    return when {
        name.isEmpty() -> "Name can't be empty"
        name == "." || name == ".." -> "Reserved name"
        name.contains('/') -> "Names can't contain \"/\""
        name.any { it.isISOControl() } -> "Invalid character"
        name.toByteArray().size > 255 -> "Too long"
        else -> null
    }
}

/**
 * Compress, with encryption as an opt-in rather than a separate menu item — the choice is about
 * this one archive, so it belongs in the dialog that names it.
 *
 * [onConfirm] hands over a CharArray the caller owns and must zero; null means an ordinary zip.
 */
@Composable
fun CompressDialog(
    title: String,
    initialName: String,
    onConfirm: (name: String, password: CharArray?) -> Unit,
    onDismiss: () -> Unit,
    /** Forces encryption on and hides the opt-in — a vault without a password is not a vault. */
    requirePassword: Boolean = false,
    confirmLabel: String = "Compress",
) {
    var name by remember { mutableStateOf(initialName) }
    var encrypt by remember { mutableStateOf(requirePassword) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    val nameIssue = nameProblem(name)
    val mismatch = encrypt && confirm.isNotEmpty() && password != confirm
    val tooShort = encrypt && password.isNotEmpty() && password.length < MIN_PASSWORD
    val ready = nameIssue == null &&
        (!encrypt || (password.length >= MIN_PASSWORD && password == confirm))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                    isError = name.isNotEmpty() && nameIssue != null,
                    supportingText = nameIssue?.takeIf { name.isNotEmpty() }?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!requirePassword) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { encrypt = !encrypt }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(checked = encrypt, onCheckedChange = null)
                        Text("Protect with a password")
                    }
                }

                if (encrypt) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("Password") },
                        isError = tooShort,
                        supportingText = {
                            Text(
                                if (tooShort) "At least $MIN_PASSWORD characters"
                                else "There is no way to recover this. Forgetting it means " +
                                    "losing the contents."
                            )
                        },
                        visualTransformation = if (reveal) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { reveal = !reveal }) {
                                Icon(
                                    if (reveal) Icons.Rounded.VisibilityOff
                                    else Icons.Rounded.Visibility,
                                    contentDescription = if (reveal) "Hide password"
                                    else "Show password",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        singleLine = true,
                        label = { Text("Confirm password") },
                        isError = mismatch,
                        supportingText = if (mismatch) {
                            { Text("Passwords don't match") }
                        } else null,
                        visualTransformation = if (reveal) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    // Stated up front rather than discovered later: this format is this app's
                    // own, and its whole reason for existing is that a standard AES zip leaves
                    // filenames readable. Both halves of that trade are the user's to know.
                    Text(
                        if (requirePassword) {
                            "A vault hides its contents and file names. It locks whenever you " +
                                "leave Files, and only Files can open it."
                        } else {
                            "Encrypted archives hide their contents and file names, but only " +
                                "Files can open them — other apps and other devices can't."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    onConfirm(
                        name.trim(),
                        if (encrypt) password.toCharArray() else null,
                    )
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks for the key to a sealed archive. [onConfirm] hands over an array the caller must zero. */
@Composable
fun PasswordDialog(
    archiveName: String,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Unlock archive",
) {
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    archiveName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = if (reveal) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                if (reveal) Icons.Rounded.VisibilityOff
                                else Icons.Rounded.Visibility,
                                contentDescription = if (reveal) "Hide password"
                                else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty(),
                onClick = { onConfirm(password.toCharArray()) },
            ) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MIN_PASSWORD = 8

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Shown only when a paste would actually collide. Three outcomes, stated in terms of what
 * happens to the existing file rather than in policy names the user has never seen.
 */
@Composable
fun ConflictDialog(
    prompt: PastePrompt,
    onPick: (ConflictPolicy) -> Unit,
    onDismiss: () -> Unit,
) {
    val verb = when (prompt.kind) {
        OpKind.Move -> "Moving"
        OpKind.Copy -> "Copying"
        // Delete never prompts (nothing to collide with) and Compress/Extract never reach a
        // PastePrompt at all — compress() and extract() in the ViewModel go straight to
        // FileOperationService.start with their own destination logic, bypassing this dialog
        // entirely. Reaching here for any of the three would mean that invariant broke.
        OpKind.Delete, OpKind.Compress, OpKind.Extract ->
            error("${prompt.kind} should never produce a paste conflict prompt")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (prompt.conflicts.size == 1) "\"${prompt.conflicts.first()}\" already exists"
                else "${prompt.conflicts.size} items already exist"
            )
        },
        text = {
            Column {
                Text("$verb here would replace existing items. What should happen?")
                if (prompt.conflicts.size > 1) {
                    Text(
                        prompt.conflicts.take(5).joinToString(", ") +
                            if (prompt.conflicts.size > 5) ", …" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Column(Modifier.padding(top = 16.dp)) {
                    ConflictOption(
                        label = "Keep both",
                        detail = "Adds a number to the new copy",
                    ) { onPick(ConflictPolicy.KeepBoth) }
                    ConflictOption(
                        label = "Replace",
                        detail = "Overwrites the existing item permanently",
                        destructive = true,
                    ) { onPick(ConflictPolicy.Overwrite) }
                    ConflictOption(
                        label = "Skip",
                        detail = "Leaves the existing item alone",
                    ) { onPick(ConflictPolicy.Skip) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConflictOption(
    label: String,
    detail: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SortSheet(
    current: SortSpec,
    showHidden: Boolean,
    onSort: (SortSpec) -> Unit,
    onToggleHidden: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetHeading("Sort by")

            SortBy.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Re-picking the active key flips direction, which is the behaviour every
                        // desktop file manager has trained people to expect from a sort header.
                        .clickable {
                            onSort(
                                if (current.by == option) current.copy(ascending = !current.ascending)
                                else current.copy(by = option, ascending = true)
                            )
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RadioButton(selected = current.by == option, onClick = null)
                    Text(option.label(), Modifier.weight(1f))
                    if (current.by == option) {
                        Icon(
                            if (current.ascending) Icons.Rounded.ArrowUpward
                            else Icons.Rounded.ArrowDownward,
                            contentDescription = if (current.ascending) "Ascending" else "Descending",
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SheetHeading("Options")

            CheckRow(
                label = "Folders first",
                checked = current.foldersFirst,
                onToggle = { onSort(current.copy(foldersFirst = !current.foldersFirst)) },
            )
            CheckRow(
                label = "Show hidden files",
                checked = showHidden,
                onToggle = onToggleHidden,
            )
        }
    }
}

private fun SortBy.label(): String = when (this) {
    SortBy.Name -> "Name"
    SortBy.Size -> "Size"
    SortBy.Modified -> "Last modified"
    SortBy.Type -> "Type"
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

/**
 * Properties for a single entry. Directory sizes arrive asynchronously — walking a large tree
 * takes real time, so the sheet opens immediately with everything cheap and fills the size in
 * when the walk finishes rather than blocking on it.
 */
@Composable
fun PropertiesSheet(properties: Properties, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entry = properties.entry

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = false, onClick = {}, label = { Text(entry.kind.name) })
                if (entry.isHidden) {
                    FilterChip(selected = false, onClick = {}, label = { Text("Hidden") })
                }
                if (!entry.file.canWrite()) {
                    FilterChip(selected = false, onClick = {}, label = { Text("Read-only") })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            PropertyRow("Path", entry.path)
            PropertyRow("Type", if (entry.isDirectory) "Folder" else entry.mimeType)
            // Only offered for media, and only once the probe has actually run — a file whose
            // header we couldn't parse shows nothing rather than a permanent spinner.
            if (entry.kind in MEDIA_KINDS && (properties.mediaPending || properties.media != null)) {
                PropertyRow(
                    label = if (entry.kind == FileKind.Audio) "Length" else "Dimensions",
                    value = if (properties.mediaPending) null else properties.media,
                )
            }
            PropertyRow(
                label = if (entry.isDirectory) "Size on disk" else "Size",
                value = properties.totalBytes?.let { Formatter.formatFileSize(context, it) },
            )
            if (entry.isDirectory) {
                PropertyRow(
                    "Contents",
                    properties.fileCount?.let { files ->
                        val dirs = properties.folderCount ?: 0
                        "$files ${plural(files, "file")}, $dirs ${plural(dirs, "folder")}"
                    },
                )
            }
            PropertyRow(
                "Modified",
                DateUtils.formatDateTime(
                    context,
                    entry.lastModified,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                        DateUtils.FORMAT_SHOW_YEAR,
                ),
            )
        }
    }
}

private val MEDIA_KINDS = setOf(FileKind.Image, FileKind.Video, FileKind.Audio)

/** Shared with the operation banner in BrowserScreen, hence not file-private. */
internal fun plural(n: Int, noun: String) = if (n == 1) noun else "${noun}s"

/** A null [value] means "still being computed" and shows a spinner in its place. */
@Composable
private fun PropertyRow(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Box(Modifier.weight(0.65f)) {
            if (value == null) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
