package com.example.easygallery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial

class FilterFragment : Fragment() {

    private val viewModel: GalleryViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_filter, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val favoritesSwitch = view.findViewById<SwitchMaterial>(R.id.favoritesSwitch)
        val showHiddenSwitch = view.findViewById<SwitchMaterial>(R.id.showHiddenSwitch)
        val personFiltersSection = view.findViewById<View>(R.id.personFiltersSection)
        val includeSection = view.findViewById<View>(R.id.includeSection)
        val excludeSection = view.findViewById<View>(R.id.excludeSection)
        val includeChipGroup = view.findViewById<ChipGroup>(R.id.includeChipGroup)
        val excludeChipGroup = view.findViewById<ChipGroup>(R.id.excludeChipGroup)

        viewModel.filterState.observe(viewLifecycleOwner) { state ->
            if (favoritesSwitch.isChecked != state.favoritesOnly) favoritesSwitch.isChecked = state.favoritesOnly
            if (showHiddenSwitch.isChecked != state.showHidden) showHiddenSwitch.isChecked = state.showHidden

            val labels = viewModel.personFilterLabels
            val hasIncludes = state.personIncludeIds.isNotEmpty()
            val hasExcludes = state.personExcludeIds.isNotEmpty()

            personFiltersSection.visibility = if (hasIncludes || hasExcludes) View.VISIBLE else View.GONE
            includeSection.visibility = if (hasIncludes) View.VISIBLE else View.GONE
            excludeSection.visibility = if (hasExcludes) View.VISIBLE else View.GONE

            includeChipGroup.removeAllViews()
            for (id in state.personIncludeIds) {
                includeChipGroup.addView(makeChip(labels[id] ?: "Person", id, isInclude = true))
            }

            excludeChipGroup.removeAllViews()
            for (id in state.personExcludeIds) {
                excludeChipGroup.addView(makeChip(labels[id] ?: "Person", id, isInclude = false))
            }
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

    private fun makeChip(label: String, clusterId: Long, isInclude: Boolean): Chip {
        val chip = Chip(requireContext())
        chip.text = label
        chip.isCloseIconVisible = true
        chip.chipIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_face)
        chip.isChipIconVisible = true
        val colorRes = if (isInclude) R.color.filter_include else R.color.filter_exclude
        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), colorRes)
        )
        chip.setOnCloseIconClickListener {
            viewModel.removePersonFilter(clusterId, requireContext())
        }
        return chip
    }
}
