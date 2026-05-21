package com.fredapp.wbooks.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.fredapp.wbooks.R
import com.fredapp.wbooks.data.book.Book

/**
 * Page 0 of the LibraryPager. Shows a "Search library" chip; tapping it opens an
 * inline text panel. Results filter book titles (and subfolders) by substring match.
 *
 * The screen does *not* scroll back to the top each time it becomes active —
 * if the user swipes away from a search results list and returns, the scroll
 * position is preserved, matching the user's mental model of "this is the same
 * page I was just on."
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LibrarySearchScreen(
    books: List<Book>,
    onBookOpen: (Book) -> Unit,
    onBack: () -> Unit,
    isActive: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    var panelOpen by remember { mutableStateOf(false) }
    val filtered = remember(query, books) {
        if (query.isBlank()) emptyList()
        else books.filter { it.title.contains(query, ignoreCase = true) }
    }
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val rotaryBehavior = RotaryScrollableDefaults.behavior(scrollableState = listState)

    // Reclaim rotary focus on page activation, on Activity resume, and when the
    // search panel closes (the BasicTextField had grabbed focus). Don't reset
    // the scroll position.
    val resumeTick = rememberResumeTick()
    LaunchedEffect(isActive, resumeTick, panelOpen) {
        if (isActive && !panelOpen) runCatching { focusRequester.requestFocus() }
    }

    // Back: close the keyboard panel first; clear the query next; otherwise
    // bubble up to the pager-level back handler to return to library.
    BackHandler(enabled = panelOpen || query.isNotBlank()) {
        when {
            panelOpen -> panelOpen = false
            query.isNotBlank() -> query = ""
        }
    }

    Scaffold(timeText = { TimeText() }) {
        if (panelOpen) {
            LibrarySearchPanel(
                onSubmit = { typed ->
                    query = typed
                    panelOpen = false
                },
                onCancel = { panelOpen = false },
            )
            return@Scaffold
        }

        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .rotaryScrollable(behavior = rotaryBehavior, focusRequester = focusRequester),
            state = listState,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "back") { BackChipRow(onClick = onBack) }
            item {
                Chip(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                        )
                    },
                    label = { Text("Search library") },
                    onClick = { panelOpen = true },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (query.isNotBlank()) {
                item { ListHeader { Text("\"$query\" - ${filtered.size}") } }
                item {
                    Chip(
                        label = { Text("Clear") },
                        onClick = { query = "" },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = "No matches",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    items(filtered, key = { it.id }) { book ->
                        Chip(
                            label = { Text(book.title) },
                            secondaryLabel = book.author?.let { author -> { Text(author) } },
                            onClick = { onBookOpen(book) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LibrarySearchPanel(
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val onSurface = MaterialTheme.colors.onSurface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Search library")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, onSurface.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isBlank()) {
                Text(
                    text = "Title",
                    color = onSurface.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.button,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = onSurface,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Start,
                ),
                cursorBrush = SolidColor(onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSubmit(text)
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Chip(
                label = { Text("Go") },
                onClick = { onSubmit(text) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.weight(1f),
            )
            Chip(
                label = { Text("Cancel") },
                onClick = onCancel,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
