package com.github.kanelbulle.ptpimport.ui.main

import android.content.Intent
import android.content.Intent.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.github.kanelbulle.ptpimport.R


class MainFragment : Fragment() {

    private val documentTreeRequestCode: Int = 12

    companion object {
        fun newInstance() = MainFragment()
    }

    private lateinit var viewModel: MainViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setHasOptionsMenu(true)

        val view = inflater.inflate(R.layout.main_fragment, container, false)
        val button = view.findViewById<Button>(R.id.import_all_button)
        viewModel = ViewModelProviders.of(this).get(MainViewModel::class.java)
        viewModel.deviceFiles.observe(
            this,
            Observer {
                if (it.size > 0) {
                    button.setText("Import " + it.size + " files")
                }
            })
        viewModel.deviceFiles.observe(this, Observer {
            button.isEnabled = !it.isEmpty()
        })
        viewModel.transferProgress.observe(this, Observer {
            view.findViewById<TextView>(R.id.transfer_status_textview).setText(
                "Transferring ${it.currentFile.name}\n Transferred ${it.currentFilesTransferred} / ${it.totalFiles} files\n${it.currentBytesTransferred} / ${it.totalBytes} bytes"
            )
        })
        button.setOnClickListener {
            button.isEnabled = false
            viewModel.copyFilesToDestination(viewModel.deviceFiles.value!!)
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshConnectedDevices()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_set_destination) {
            val selectFile = Intent(ACTION_OPEN_DOCUMENT_TREE)
            selectFile.addFlags(FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            selectFile.addFlags(FLAG_GRANT_READ_URI_PERMISSION)
            selectFile.addFlags(FLAG_GRANT_WRITE_URI_PERMISSION)
            startActivityForResult(selectFile, documentTreeRequestCode)
        } else {
            return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == documentTreeRequestCode) {
            data?.data?.let {
                viewModel.setDestinationDirectory(it)
                context?.contentResolver?.takePersistableUriPermission(
                    it, FLAG_GRANT_READ_URI_PERMISSION.or(
                        FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                )
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }
}
