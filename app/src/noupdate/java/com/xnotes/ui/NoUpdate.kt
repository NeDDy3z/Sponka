package com.xnotes.ui

import androidx.compose.runtime.Composable

// Store builds update through their store, so the GitHub updater is left out of them.

@Composable
@Suppress("UNUSED_PARAMETER")
fun UpdatePrompt(ed: Editor, ready: Boolean, rebuilt: Boolean) = Unit

@Composable
@Suppress("UNUSED_PARAMETER")
fun UpdateSection(checkOnStartup: Boolean, onCheckOnStartup: (Boolean) -> Unit) = Unit
