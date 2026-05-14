package com.example.easygallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.switchmaterial.SwitchMaterial

class FilterFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_filter, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val favoritesSwitch = view.findViewById<SwitchMaterial>(R.id.favoritesSwitch)
        val showHiddenSwitch = view.findViewById<SwitchMaterial>(R.id.showHiddenSwitch)

        viewModel.filterState.observe(viewLifecycleOwner) { state ->
            if (favoritesSwitch.isChecked != state.favoritesOnly) favoritesSwitch.isChecked = state.favoritesOnly
            if (showHiddenSwitch.isChecked != state.showHidden) showHiddenSwitch.isChecked = state.showHidden
        }

        favoritesSwitch.setOnCheckedChangeListener { _, checked ->
            if (viewModel.filterState.value?.favoritesOnly != checked) {
                viewModel.setFavoritesOnly(checked, requireContext())
            }
        }

        showHiddenSwitch.setOnCheckedChangeListener { _, checked ->
            if (viewModel.filterState.value?.showHidden != checked) {
                viewModel.setShowHidden(checked, requireContext())
            }
        }
    }
}
