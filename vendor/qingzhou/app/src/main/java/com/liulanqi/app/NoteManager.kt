package com.liulanqi.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object NoteManager {

private const val NOTES_FILE = "notes.json"

data class Note(
val id: String,
var title: String,
var content: String,
val createTime: Long,
var updateTime: Long
)

private fun getNotesFile(context: Context): File {
return File(context.filesDir, NOTES_FILE)
}

fun getAllNotes(context: Context): List<Note> {
val file = getNotesFile(context)
if (!file.exists()) return emptyList()
val json = file.readText()
val arr = JSONArray(json)
val notes = mutableListOf<Note>()
for (i in 0 until arr.length()) {
val obj = arr.getJSONObject(i)
notes.add(
Note(
id = obj.getString("id"),
title = obj.getString("title"),
content = obj.getString("content"),
createTime = obj.getLong("createTime"),
updateTime = obj.getLong("updateTime")
)
)
}
return notes.sortedByDescending { it.updateTime }
}

fun getNoteById(context: Context, id: String): Note? {
return getAllNotes(context).find { it.id == id }
}

fun saveNote(context: Context, note: Note) {
val notes = getAllNotes(context).toMutableList()
val existingIndex = notes.indexOfFirst { it.id == note.id }
if (existingIndex >= 0) {
notes[existingIndex] = note
} else {
notes.add(0, note)
}
writeNotes(context, notes)
}

fun deleteNote(context: Context, id: String) {
val notes = getAllNotes(context).toMutableList()
notes.removeAll { it.id == id }
writeNotes(context, notes)
}

private fun writeNotes(context: Context, notes: List<Note>) {
val arr = JSONArray()
for (note in notes) {
val obj = JSONObject()
obj.put("id", note.id)
obj.put("title", note.title)
obj.put("content", note.content)
obj.put("createTime", note.createTime)
obj.put("updateTime", note.updateTime)
arr.put(obj)
}
getNotesFile(context).writeText(arr.toString())
}
}