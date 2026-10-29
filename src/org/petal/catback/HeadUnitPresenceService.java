package org.petal.catback;

import android.annotation.TargetApi;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.net.MacAddress;
import android.util.Log;

// system binds us when the car bt connects, while bound we can start the fgs
@TargetApi(31) // 12+ only
public final class HeadUnitPresenceService extends CompanionDeviceService {
    // 13+ calls these, 12 gets the address ones below
    @TargetApi(33) @Override public void onDeviceAppeared(AssociationInfo association) { appeared(address(association)); }
    @TargetApi(33) @Override public void onDeviceDisappeared(AssociationInfo association) { disappeared(address(association)); }
    @SuppressWarnings("deprecation")
    @Override public void onDeviceAppeared(String address) { appeared(address); }
    @SuppressWarnings("deprecation")
    @Override public void onDeviceDisappeared(String address) { disappeared(address); }

    private void appeared(String address) {
        if (!CarLink.isCar(this, address)) return;
        Log.i("Catback", "Linked car connected; starting projection");
        try { CarLink.connect(this); }
        catch (RuntimeException e) { Log.w("Catback", "Could not start projection from background", e); }
    }
    private void disappeared(String address) {
        if (!CarLink.isCar(this, address)) return;
        try { CarLink.disconnected(this); } catch (RuntimeException ignored) { /* not running, who cares */ }
    }
    @TargetApi(33)
    private static String address(AssociationInfo association) {
        MacAddress mac = association.getDeviceMacAddress();
        return mac == null ? null : mac.toString();
    }
}
