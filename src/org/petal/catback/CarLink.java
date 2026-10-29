package org.petal.catback;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import org.petal.catback.protocol.WifiBootstrap;

// the one linked car + its cdm association so android lets us start in the bg
public final class CarLink {
    private static final String PREFS = "car", ADDRESS = "address", NAME = "name";
    private CarLink() { }

    public static String address(Context context) { return prefs(context).getString(ADDRESS, null); }
    public static String name(Context context) {
        String name = prefs(context).getString(NAME, null);
        return name != null ? name : "your car";
    }
    public static boolean isCar(Context context, String address) {
        String car = address(context);
        return car != null && car.equalsIgnoreCase(address);
    }
    static void save(Context context, String address, String name) {
        prefs(context).edit().putString(ADDRESS, address.toUpperCase(java.util.Locale.ROOT))
                .putString(NAME, name).apply();
    }
    public static void forget(Context context) {
        String address = address(context);
        CompanionDeviceManager cdm = manager(context);
        if (address != null && cdm != null) {
            try { if (Build.VERSION.SDK_INT >= 31) cdm.stopObservingDevicePresence(address); }
            catch (RuntimeException ignored) { }
            try { cdm.disassociate(address); } catch (RuntimeException ignored) { }
        }
        prefs(context).edit().clear().apply();
        context.stopService(new Intent(context, ProjectionService.class));
    }

    // 13+ pings HeadUnitPresenceService when the car bt comes and goes
    static boolean observe(Context context, String address) {
        CompanionDeviceManager cdm = manager(context);
        if (cdm == null || Build.VERSION.SDK_INT < 33) return false;
        try { cdm.startObservingDevicePresence(address); return true; }
        catch (RuntimeException e) { return false; }
    }
    static boolean associated(Context context, String address) {
        CompanionDeviceManager cdm = manager(context);
        if (cdm == null) return false;
        for (String known : cdm.getAssociations()) if (known.equalsIgnoreCase(address)) return true;
        return false;
    }
    static AssociationRequest request(String address) {
        return new AssociationRequest.Builder().setSingleDevice(true)
                .addDeviceFilter(new BluetoothDeviceFilter.Builder().setAddress(address).build()).build();
    }
    static CompanionDeviceManager manager(Context context) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) return null;
        return context.getSystemService(CompanionDeviceManager.class);
    }

    // linked car, or anything bonded that shouts the AA wireless uuid
    @SuppressLint("MissingPermission") // SecurityException catch covers getUuids
    static boolean isHeadUnit(Context context, BluetoothDevice device) {
        if (isCar(context, device.getAddress())) return true;
        try {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids != null) for (ParcelUuid uuid : uuids)
                if (WifiBootstrap.SERVICE.equals(uuid.getUuid()) || WifiBootstrap.REVERSED_SERVICE.equals(uuid.getUuid())) return true;
        } catch (SecurityException noBluetoothPermission) { }
        return false;
    }

    // spam safe
    static void connect(Context context) {
        String address = address(context);
        if (address != null) connect(context, address);
    }
    static void connect(Context context, String address) {
        context.startForegroundService(new Intent(context, ProjectionService.class)
                .setAction(ProjectionService.WIRELESS).putExtra(ProjectionService.EXTRA_DEVICE, address)
                .putExtra(ProjectionService.EXTRA_AUTO, true));
    }
    static void disconnected(Context context) { disconnected(context, address(context)); }
    static void disconnected(Context context, String address) {
        context.startService(new Intent(context, ProjectionService.class).setAction(ProjectionService.CAR_GONE)
                .putExtra(ProjectionService.EXTRA_DEVICE, address));
    }
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
