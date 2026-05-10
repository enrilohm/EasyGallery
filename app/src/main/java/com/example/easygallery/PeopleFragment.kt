package com.example.easygallery

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PeopleFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()

    private data class ContactItem(val id: Long, val name: String, val photoUri: Uri)

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_people, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        swipeRefresh.setOnRefreshListener { loadContacts() }

        recyclerView = view.findViewById(R.id.peopleRecyclerView)
        emptyText = view.findViewById(R.id.emptyText)

        val pad = (4 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad, pad, pad, bars.bottom + pad)
            insets
        }

        loadContacts()
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            title = "People"
            setDisplayHomeAsUpEnabled(false)
        }
    }

    private fun loadContacts() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            emptyText.text = "Contacts permission not granted."
            emptyText.visibility = View.VISIBLE
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1)
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val contacts = withContext(Dispatchers.IO) { queryContacts() }
            if (contacts.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyText.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
                recyclerView.adapter = PeopleAdapter(contacts)
            }
            swipeRefresh.isRefreshing = false
        }
    }

    private fun queryContacts(): List<ContactItem> {
        val resolver = requireContext().contentResolver
        val contacts = mutableListOf<ContactItem>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_URI
            ),
            "${ContactsContract.Contacts.PHOTO_URI} IS NOT NULL",
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val photoCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val photoUri = cursor.getString(photoCol) ?: continue
                contacts.add(ContactItem(id, name, Uri.parse(photoUri)))
            }
        }
        return contacts
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            loadContacts()
    }

    private inner class PeopleAdapter(private val items: List<ContactItem>) :
        RecyclerView.Adapter<PeopleAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val photo: ImageView = view.findViewById(R.id.contactPhoto)
            val name: TextView = view.findViewById(R.id.contactName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_person_tile, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.photo.load(item.photoUri) { crossfade(true) }
            holder.itemView.setOnClickListener { onContactClick(item) }
        }
    }

    private fun onContactClick(contact: ContactItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val rawPaths = withContext(Dispatchers.IO) { findPhotosForContact(contact) }
            val paths = viewModel.applyFilters(rawPaths)
            if (paths.isEmpty()) {
                Toast.makeText(requireContext(), "No matching photos found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            ClusterImagesSheet.showPaths(parentFragmentManager, paths, contact.name)
        }
    }

    private suspend fun findPhotosForContact(contact: ContactItem): List<String> {
        val context = requireContext().applicationContext
        VectorStore.init(context)
        FaceEncoder.load(context)

        val bitmap = loadBitmap(contact.photoUri) ?: return emptyList()
        val faces = FaceDetector.detect(bitmap)
        if (faces.isEmpty()) return emptyList()

        val embedding = FaceEncoder.encode(faces.first().crop) ?: return emptyList()
        return VectorStore.findSimilarFaces(embedding, 50)
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(requireContext().contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(requireContext().contentResolver, uri)
        }
    } catch (e: Exception) {
        null
    }
}
