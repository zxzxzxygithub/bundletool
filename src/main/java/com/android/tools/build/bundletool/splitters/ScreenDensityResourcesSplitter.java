/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.splitters;

import static com.android.tools.build.bundletool.utils.ResourcesUtils.DEFAULT_DENSITY_VALUE;
import static com.android.tools.build.bundletool.utils.ResourcesUtils.MIPMAP_TYPE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.android.aapt.ConfigurationOuterClass.Configuration;
import com.android.aapt.Resources.ConfigValue;
import com.android.aapt.Resources.Entry;
import com.android.aapt.Resources.Package;
import com.android.aapt.Resources.ResourceTable;
import com.android.aapt.Resources.Type;
import com.android.bundle.Targeting.ScreenDensity;
import com.android.bundle.Targeting.ScreenDensity.DensityAlias;
import com.android.bundle.Targeting.ScreenDensityTargeting;
import com.android.tools.build.bundletool.model.ModuleSplit;
import com.android.tools.build.bundletool.model.PackageTypeEntry;
import com.android.tools.build.bundletool.targeting.ScreenDensitySelector;
import com.android.tools.build.bundletool.utils.ResourcesUtils;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Splits module resources by screen density. */
public class ScreenDensityResourcesSplitter extends SplitterForOneTargetingDimension {

  static final ImmutableSet<DensityAlias> DEFAULT_DENSITY_BUCKETS =
      ImmutableSet.of(
          DensityAlias.LDPI,
          DensityAlias.MDPI,
          DensityAlias.HDPI,
          DensityAlias.XHDPI,
          DensityAlias.XXHDPI,
          DensityAlias.XXXHDPI,
          DensityAlias.TVDPI);

  private final ImmutableSet<DensityAlias> densityBuckets;

  public ScreenDensityResourcesSplitter() {
    this(DEFAULT_DENSITY_BUCKETS);
  }

  public ScreenDensityResourcesSplitter(ImmutableSet<DensityAlias> densityBuckets) {
    this.densityBuckets = densityBuckets;
  }

  @Override
  public ImmutableCollection<ModuleSplit> splitInternal(ModuleSplit split) {
    Optional<ResourceTable> resourceTable = split.getResourceTable();

    // No resource tables means no resources, hence nothing to split here.
    if (!resourceTable.isPresent()
        || resourceTable.get().equals(ResourceTable.getDefaultInstance())) {
      return ImmutableList.of(split);
    }

    ImmutableList.Builder<ModuleSplit> splitsBuilder = new ImmutableList.Builder<>();
    for (DensityAlias density : densityBuckets) {
      ResourceTable optimizedTable = filterResourceTableForDensity(resourceTable.get(), density);
      // Don't generate empty splits.
      if (optimizedTable.equals(ResourceTable.getDefaultInstance())) {
        continue;
      }
      ModuleSplit.Builder moduleSplitBuilder =
          split
              .toBuilder()
              .setTargeting(
                  split
                      .getTargeting()
                      .toBuilder()
                      .setScreenDensityTargeting(
                          ScreenDensityTargeting.newBuilder()
                              .addValue(toScreenDensity(density))
                              .addAllAlternatives(
                                  allBut(densityBuckets, density)
                                      .stream()
                                      .map(ScreenDensityResourcesSplitter::toScreenDensity)
                                      .collect(toImmutableList())))
                      .build())
              .setMasterSplit(false)
              .setEntries(ModuleSplit.filterResourceEntries(split.getEntries(), optimizedTable))
              .setResourceTable(optimizedTable);
      splitsBuilder.add(moduleSplitBuilder.build());
    }

    ModuleSplit defaultResourcesSplit = getDefaultResourcesSplit(split, splitsBuilder.build());
    return splitsBuilder.add(defaultResourcesSplit).build();
  }

  private static ScreenDensity toScreenDensity(DensityAlias alias) {
    return ScreenDensity.newBuilder().setDensityAlias(alias).build();
  }

  /** Creates resources split with no extra targeting with all other unclaimed resource entries. */
  private ModuleSplit getDefaultResourcesSplit(
      ModuleSplit inputSplit, ImmutableCollection<ModuleSplit> densitySplits) {
    ResourceTable defaultSplitTable =
        getResourceTableForDefaultSplit(inputSplit, getClaimedConfigs(densitySplits));
    return inputSplit
        .toBuilder()
        .setEntries(ModuleSplit.filterResourceEntries(inputSplit.getEntries(), defaultSplitTable))
        .setResourceTable(defaultSplitTable)
        .build();
  }

  private ImmutableMultimap<PackageTypeEntry, ConfigValue> getClaimedConfigs(
      Iterable<ModuleSplit> moduleSplits) {
    ImmutableMultimap.Builder<PackageTypeEntry, ConfigValue> result =
        new ImmutableMultimap.Builder<>();
    for (ModuleSplit moduleSplit : moduleSplits) {
      checkState(
          moduleSplit.getResourceTable().isPresent(),
          "Resource table not found in the density split.");
      for (Package pkg : moduleSplit.getResourceTable().get().getPackageList()) {
        for (Type type : pkg.getTypeList()) {
          for (Entry entry : type.getEntryList()) {
            for (ConfigValue configValue : entry.getConfigValueList()) {
              result.put(PackageTypeEntry.create(pkg, type, entry), configValue);
            }
          }
        }
      }
    }
    return result.build();
  }

  /**
   * Returns a resource table for master split.
   *
   * <p>It will be stripped of any entries claimed by the config splits.
   */
  private ResourceTable getResourceTableForDefaultSplit(
      ModuleSplit split, ImmutableMultimap<PackageTypeEntry, ConfigValue> claimedConfigs) {
    checkArgument(
        split.getResourceTable().isPresent(), "Expected the split to contain Resource Table.");
    ResourceTable.Builder prunedTable = split.getResourceTable().get().toBuilder();
    for (Package.Builder packageBuilder : prunedTable.getPackageBuilderList()) {
      for (Type.Builder typeBuilder : packageBuilder.getTypeBuilderList()) {
        List<Entry> newEntries = new ArrayList<>();
        for (Entry entry : typeBuilder.getEntryList()) {
          ImmutableList<ConfigValue> allConfigsExceptClaimed =
              entry
                  .getConfigValueList()
                  .stream()
                  .filter(
                      configValue ->
                          !claimedConfigs.containsEntry(
                              PackageTypeEntry.create(packageBuilder, typeBuilder, entry),
                              configValue))
                  .collect(toImmutableList());
          Entry.Builder newEntry =
              entry.toBuilder().clearConfigValue().addAllConfigValue(allConfigsExceptClaimed);
          if (newEntry.getConfigValueCount() > 0) { // if everything was claimed we skip the entry.
            newEntries.add(newEntry.build());
          }
        }
        typeBuilder.clearEntry().addAllEntry(newEntries);
      }
    }
    return prunedTable.build();
  }

  private ResourceTable filterResourceTableForDensity(ResourceTable input, DensityAlias density) {
    return ResourcesUtils.filterResourceTable(
        input,
        // Put mipmaps into the master split.
        /* removeTypePredicate= */ type -> type.getName().equals(MIPMAP_TYPE),
        /* configValuesFilterFn= */ entry -> filterEntryForDensity(entry, density));
  }

  /**
   * Only leaves the density specific config values optimized for a given density.
   *
   * <p>As any other resource qualifiers can be requested when delivering resources, the algorithm
   * chooses the best match only within group of resources differing by density only.
   *
   * @param initialEntry the entry to be updated
   * @param targetDensity the desired density to match
   * @return the entry with the best matching density config values.
   */
  private Entry filterEntryForDensity(Entry initialEntry, DensityAlias targetDensity) {
    // Groups together configs that only differ on density.
    ImmutableList<List<ConfigValue>> densityGroups =
        ImmutableList.copyOf(
            initialEntry
                .getConfigValueList()
                .stream()
                // The below filter resembles how aapt2 works today. This filtering should be
                // reconsidered when we start supporting non-platform dimension(s).
                .filter(
                    configValue -> configValue.getConfig().getDensity() != DEFAULT_DENSITY_VALUE)
                .collect(
                    Collectors.groupingBy(configValue -> clearDensity(configValue.getConfig())))
                .values());
    ImmutableList<ConfigValue> valuesToKeep =
        pickBestDensityForEachGroup(densityGroups, targetDensity);
    return initialEntry.toBuilder().clearConfigValue().addAllConfigValue(valuesToKeep).build();
  }

  /** For each density group, it picks the best match for a given desired densityAlias. */
  private ImmutableList<ConfigValue> pickBestDensityForEachGroup(
      ImmutableList<List<ConfigValue>> densityGroups, DensityAlias densityAlias) {
    return densityGroups
        .stream()
        .flatMap(
            group ->
                new ScreenDensitySelector()
                    .selectAllMatchingConfigValues(
                        ImmutableList.copyOf(group),
                        densityAlias,
                        allBut(densityBuckets, densityAlias))
                    .stream())
        .collect(toImmutableList());
  }

  private static Set<DensityAlias> allBut(
      ImmutableSet<DensityAlias> splitByDensities, DensityAlias densityAlias) {
    return Sets.difference(splitByDensities, ImmutableSet.of(densityAlias));
  }

  private static Configuration clearDensity(Configuration source) {
    return source.toBuilder().clearDensity().build();
  }
}
