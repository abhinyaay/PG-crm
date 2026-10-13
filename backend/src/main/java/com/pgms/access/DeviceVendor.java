package com.pgms.access;

/** Integration flavour: ADMS push (the device calls /iclock) or a bridge / door controller using the device key. */
public enum DeviceVendor {
    ZKTECO_ADMS,
    ESSL_ADMS,
    GENERIC_WEBHOOK
}
