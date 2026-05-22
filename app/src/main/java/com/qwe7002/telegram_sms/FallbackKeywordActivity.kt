package com.qwe7002.telegram_sms

import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.qwe7002.telegram_sms.value.Const
import com.tencent.mmkv.MMKV

class FallbackKeywordActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fallback_keyword_list)
        MMKV.initialize(this)
        val inflater = this.layoutInflater
        val fab = findViewById<FloatingActionButton>(R.id.fallback_keyword_fab)
        val keywordListView = findViewById<ListView>(R.id.fallback_keyword_list)
        // Handle window insets for edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(keywordListView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        FakeStatusBar().fakeStatusBar(this, window)
        val preferences = MMKV.defaultMMKV()
        val fallbackKeywordList =
            preferences.getStringSet("fallback_keyword_list", setOf())?.toMutableList()
                ?: mutableListOf()
        val keywordListAdapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            fallbackKeywordList
        )
        keywordListView.adapter = keywordListAdapter
        keywordListView.onItemClickListener =
            OnItemClickListener { _: AdapterView<*>?, _: View?, position: Int, _: Long ->
                val dialogView = inflater.inflate(R.layout.set_keyword_layout, null)
                val editText =
                    dialogView.findViewById<EditText>(R.id.spam_sms_keyword_editview)
                editText.setText(fallbackKeywordList[position])
                AlertDialog.Builder(this@FallbackKeywordActivity)
                    .setTitle(R.string.fallback_keyword_edit_title)
                    .setView(dialogView)
                    .setPositiveButton(R.string.ok_button) { _: DialogInterface?, _: Int ->
                        fallbackKeywordList[position] = editText.text.toString()
                        saveAndFlush(fallbackKeywordList, keywordListAdapter)
                    }
                    .setNeutralButton(R.string.cancel_button, null)
                    .setNegativeButton(
                        R.string.delete_button,
                        ((DialogInterface.OnClickListener { _: DialogInterface?, _: Int ->
                            fallbackKeywordList.removeAt(position)
                            saveAndFlush(fallbackKeywordList, keywordListAdapter)
                        }))
                    )
                    .show()
            }

        fab.setOnClickListener {
            val dialogView = inflater.inflate(R.layout.set_keyword_layout, null)
            val editText = dialogView.findViewById<EditText>(R.id.spam_sms_keyword_editview)
            AlertDialog.Builder(this@FallbackKeywordActivity)
                .setTitle(R.string.fallback_keyword_add_title)
                .setView(dialogView)
                .setPositiveButton(R.string.ok_button) { _: DialogInterface?, _: Int ->
                    fallbackKeywordList.add(editText.text.toString())
                    saveAndFlush(fallbackKeywordList, keywordListAdapter)
                }
                .setNeutralButton(R.string.cancel_button, null)
                .show()
        }
    }

    private fun saveAndFlush(
        fallbackKeywordList: MutableList<String>,
        listAdapter: ArrayAdapter<String>
    ) {
        Log.d(Const.TAG, fallbackKeywordList.toString())
        MMKV.defaultMMKV().encode("fallback_keyword_list", fallbackKeywordList.toSet())
        listAdapter.notifyDataSetChanged()
    }
}
