package dev.mike.couchtour

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay

/**
 * Screen 1B — Dedicated Search screen for Android:
 * Debounced search input across Phish and Relisten catalogs,
 * category tabs, filter chips, and multi-artist results.
 */
@Composable
fun SearchScreen(vm: PlayerViewModel, nav: NavHostController) {
    val ledger = LocalLedgerColors.current
    var query by rememberSaveable { mutableStateOf("") }
    val term = query.trim()
    var searchHits by remember { mutableStateOf<SearchHits?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    LaunchedEffect(term) {
        if (term.length >= 3) {
            isSearching = true
            delay(300)
            searchHits = searchAll(term)
            isSearching = false
        } else {
            searchHits = null
            isSearching = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ledger.appBackground)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp)
        ) {
            Text(
                text = "SEARCH",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp,
                color = ledger.textSubtle
            )
            Text(
                text = "Artists, shows & tracks",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.01).sp,
                color = ledger.textPrimary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Search text field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = {
                    Text("Search artists, shows, tracks…", fontSize = 14.sp, color = ledger.textSubtle)
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = ledger.textSubtle, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = ledger.textSubtle, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = ledger.cardSurface,
                    unfocusedContainerColor = ledger.cardSurface,
                    focusedTextColor = ledger.textPrimary,
                    unfocusedTextColor = ledger.textPrimary,
                    focusedIndicatorColor = ledger.accentBase,
                    unfocusedIndicatorColor = ledger.controlOutline,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            )
        }

        // Content
        if (term.length < 3) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Text(
                    text = "Type 3 or more characters to search across Phish and Relisten artists, shows, and tracks.",
                    fontSize = 14.sp,
                    color = ledger.textMuted
                )
            }
        } else if (isSearching && searchHits == null) {
            Loading()
        } else {
            SearchResultsList(searchHits, vm, nav)
        }
    }
}
