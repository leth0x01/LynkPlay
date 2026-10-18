package com.shilapi.xcertplay

import android.content.res.Resources
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydVehicleCapabilityProbe
import com.shilapi.xcertplay.media.AmbientMusicSettings
import com.shilapi.xcertplay.media.AmbientMusicController
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
import com.shilapi.xcertplay.shared.R
import com.shilapi.xcertplay.vehicle.VehicleIntegration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** 用目标应用资源覆盖模拟 LynkPlay，库的默认资源继续覆盖上游通用测试。 */
@Implements(Resources::class)
class LynkVehicleResources {
    @RealObject lateinit var resources: Resources
    @Implementation fun getBoolean(id: Int): Boolean =
        if (id == R.bool.config_byd_integration) false
        else Shadow.directlyOn(resources, Resources::class.java, "getBoolean", ClassParameter.from(Int::class.javaPrimitiveType, id))

    @Implementation fun getString(id: Int): String =
        if (id == R.string.config_vehicle_profile) VehicleIntegration.LYNK_06_2023_PETROL
        else Shadow.directlyOn(resources, Resources::class.java, "getString", ClassParameter.from(Int::class.javaPrimitiveType, id))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [LynkVehicleResources::class])
class LynkVehicleIntegrationTest {
    @Test fun targetBlocksBydPackagesAndSavedOptInsWithoutDeletingPreferences() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply { packageName = "com.byd.carsettings" }
        })
        BydOutputSettings.setEnabled(app, true)
        BydOutputSettings.setBatteryToIphone(app, true)
        BydOutputSettings.setWheelSpeedToIphone(app, true)
        BydOutputSettings.setVideoWhileParked(app, true)
        BydOutputSettings.setClusterSong(app, true)
        BydOutputSettings.setCarPlayCalls(app, true)
        BydOutputSettings.setCarPlayCallControls(app, true)
        BydOutputSettings.setFceCluster(app, true)
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        AirPlayPersistence.saveLegacyClusterEnabled(app, true)
        AmbientMusicSettings.save(app, AmbientMusicSettings.Values(enabled = true, carRhythm = true, carRhythmFeed = true))

        assertEquals(VehicleIntegration.LYNK_06_2023_PETROL, VehicleIntegration.profile(app))
        assertFalse(BydOutputSettings.available(app))
        assertFalse(BydOutputSettings.navigationAvailable(app))
        assertFalse(BydOutputSettings.enabled(app))
        assertFalse(BydOutputSettings.batteryToIphoneActive(app))
        assertFalse(BydOutputSettings.wheelSpeedToIphoneActive(app))
        assertFalse(BydOutputSettings.videoWhileParkedActive(app))
        assertFalse(BydOutputSettings.clusterSong(app))
        assertFalse(BydOutputSettings.carPlayCalls(app))
        assertFalse(BydOutputSettings.carPlayCallControls(app))
        assertFalse(BydOutputSettings.fceCluster(app))
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(app))
        assertFalse(AirPlayPersistence.loadLegacyClusterEnabled(app))
        assertFalse(CarHotspotSetup.isBydHeadUnit(app))
        assertFalse(AmbientMusicSettings.load(app).enabled)
        assertFalse(AmbientMusicSettings.load(app).carRhythm)
        assertFalse(AmbientMusicSettings.load(app).carRhythmFeed)
        assertTrue(app.getSharedPreferences("ambient_music", 0).getBoolean("carRhythm", false))
        assertTrue(app.getSharedPreferences("ambient_music", 0).getBoolean("carRhythmFeed", false))
        assertFalse(AmbientMusicController.checkSupport(app).get())
        assertTrue(app.getSharedPreferences("diplay_byd_outputs", 0).getBoolean("battery_to_iphone", false))
        BydNavigationOutputs.onAppOpened(app)
        BydNavigationOutputs.start(app)
        assertThrows(IllegalStateException::class.java) { BydAdbAccess.check(app, mayAsk = false) }
        assertThrows(IllegalStateException::class.java) { BydVehicleCapabilityProbe.probe(app) }
        assertTrue(VehicleIntegration.diagnosticReport(app).contains("BYD integration: false"))
    }

    @Test fun guideOffersOnlyGenericFeaturesWithoutClaimingVehicleValidation() {
        for (generation in DiLinkGeneration.entries) {
            val features = SetupGuide.features(generation, bydIntegration = false)
            assertEquals(listOf(SetupGuide.Feature.AUTO_CONNECT, SetupGuide.Feature.LOCATION), features.map { it.feature })
            assertTrue(features.all { it.status != SetupGuide.Status.TESTED && !it.needsAdb })
        }
    }
}
