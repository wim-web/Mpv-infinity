package app.infinity.mpvz.ui.browser.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons

/** Shared chrome for the local video and music libraries. */
@Composable
internal fun LibraryHeaderColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(modifier.fillMaxWidth().background(
    if (MaterialTheme.colorScheme.background == Color.Black) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
  ), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryTabs(titles: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
  PrimaryScrollableTabRow(
    selectedTabIndex = selectedIndex,
    containerColor = Color.Transparent,
    contentColor = MaterialTheme.colorScheme.onSurface,
    edgePadding = 8.dp,
    divider = {},
  ) {
    titles.forEachIndexed { index, title ->
      Tab(selected = selectedIndex == index, onClick = { onSelect(index) }, text = {
        Text(title, style = MaterialTheme.typography.titleMedium,
          fontWeight = if (selectedIndex == index) FontWeight.Bold else FontWeight.Medium,
          maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
      }, selectedContentColor = MaterialTheme.colorScheme.onSurface,
        unselectedContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
    }
  }
  HorizontalDivider()
}

@Composable
internal fun LibrarySourceSelector(
  title: String,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  icon: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  menu: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier.clip(RoundedCornerShape(20.dp)).clickable { onExpandedChange(true) },
    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.82f),
  ) {
    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
      icon()
      Spacer(Modifier.width(6.dp))
      Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
      Spacer(Modifier.width(4.dp))
      Icon(Icons.RoundedFilled.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }, content = menu)
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LibraryCreatePlaylistButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  FloatingActionButtonMenu(modifier = modifier, expanded = false, button = {
    ToggleFloatingActionButton(checked = false, onCheckedChange = { onClick() }) {
      Icon(Icons.RoundedFilled.Add, contentDescription = "New Playlist")
    }
  }) { }
}
