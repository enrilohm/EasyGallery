package com.example.easygallery

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.easygallery.gallery.GalleryFragment
import com.example.easygallery.search.SearchFragment
import com.example.easygallery.objects.ObjectBrowseFragment
import com.example.easygallery.map.MapFragment
import com.example.easygallery.faces.PeopleFragment
import com.example.easygallery.gallery.FilterFragment
import com.example.easygallery.gallery.TimelineFragment

enum class TabType(val stableId: Long, val label: String) {
    GALLERY(0, "Gallery"),
    TIMELINE(6, "Timeline"),
    SEARCH(1, "Search"),
    OBJECTS(2, "Objects"),
    MAP(3, "Map"),
    PEOPLE(4, "People"),
    FILTER(5, "Filter"),
}

class GalleryPagerAdapter(
    activity: FragmentActivity,
    val tabs: List<TabType>
) : FragmentStateAdapter(activity) {

    override fun getItemCount() = tabs.size
    override fun getItemId(position: Int) = tabs[position].stableId
    override fun containsItem(itemId: Long) = tabs.any { it.stableId == itemId }

    override fun createFragment(position: Int): Fragment = when (tabs[position]) {
        TabType.GALLERY   -> GalleryFragment()
        TabType.TIMELINE  -> TimelineFragment()
        TabType.SEARCH    -> SearchFragment()
        TabType.OBJECTS -> ObjectBrowseFragment()
        TabType.MAP     -> MapFragment()
        TabType.PEOPLE  -> PeopleFragment()
        TabType.FILTER  -> FilterFragment()
    }
}
