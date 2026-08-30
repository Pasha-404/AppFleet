package ru.pashaapps.appfleet.domain;

import java.util.List;

public sealed interface AssetSelection permits AssetSelection.Selected, AssetSelection.NeedsChoice, AssetSelection.None {
    record Selected(ReleaseAsset asset, boolean fromManifest) implements AssetSelection { }
    record NeedsChoice(List<ReleaseAsset> candidates) implements AssetSelection {
        public NeedsChoice { candidates = List.copyOf(candidates); }
    }
    record None(String reason) implements AssetSelection { }
}

