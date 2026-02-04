/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.location

import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

fun MapLibreMap?.zoomToLocation(locationData: LocationData, preserveCurrentZoomLevel: Boolean = false) {
    val zoomLevel = if (preserveCurrentZoomLevel && this?.cameraPosition != null) {
        cameraPosition.zoom
    } else {
        INITIAL_MAP_ZOOM_IN_PREVIEW
    }
    val expectedCameraPosition = CameraPosition.Builder()
            .target(LatLng(locationData.latitude, locationData.longitude))
            .zoom(zoomLevel)
            .build()
    val cameraUpdate = CameraUpdateFactory.newCameraPosition(expectedCameraPosition)
    this?.easeCamera(cameraUpdate)
}

fun MapLibreMap?.zoomToBounds(latLngBounds: LatLngBounds) {
    this?.getCameraForLatLngBounds(latLngBounds)?.let { camPosition ->
        // unZoom a little to avoid having pins exactly at the edges of the map
        cameraPosition = CameraPosition.Builder(camPosition)
                .zoom((camPosition.zoom - 1).coerceAtLeast(MapLibreConstants.MINIMUM_ZOOM.toDouble()))
                .build()
    }
}
