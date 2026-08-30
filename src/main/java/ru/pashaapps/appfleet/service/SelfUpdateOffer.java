package ru.pashaapps.appfleet.service;

import ru.pashaapps.appfleet.domain.AppFleetManifest;
import ru.pashaapps.appfleet.domain.GithubRelease;
import ru.pashaapps.appfleet.domain.ReleaseAsset;

public record SelfUpdateOffer(String currentVersion, GithubRelease release, AppFleetManifest manifest, ReleaseAsset installer) { }

