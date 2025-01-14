package com.willowtree.vocable.presets

import android.content.Context
import com.willowtree.vocable.room.PresetCategoryDto
import com.willowtree.vocable.room.VocableDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomPresetCategoriesRepository(
    context: Context
) : PresetCategoriesRepository {
    // Needed because we're checking database contents and making updates based
    // on them. Multiple callers may call at once.
    private val categoryMutex = Mutex()
    private val database = VocableDatabase.getVocableDatabase(context)

    override suspend fun getPresetCategories(): List<Category.PresetCategory> {
        return categoryMutex.withLock {
            val presetDtos = database.presetCategoryDao().getPresetCategoryDtos()
            PresetCategories.values().filter { it != PresetCategories.MY_SAYINGS }
                .mapIndexed { i, presetCategory ->
                    var presetDto = presetDtos.firstOrNull { it.categoryId == presetCategory.id }
                    if (presetDto == null) {
                        presetDto = PresetCategoryDto(presetCategory.id, false, i)
                        database.presetCategoryDao().insertPresetCategory(presetDto)
                    }
                    Category.PresetCategory(
                        presetCategory.id,
                        presetDto.sortOrder,
                        presetDto.hidden,
                        presetCategory.getNameId()
                    )
                }
        }
    }
}