package com.pittech.devices

internal val PolarisOperation.path: String get() = when(this) {
    PolarisOperation.REQUEST_CODE -> "/email/m2s/send/verify/code"
    PolarisOperation.SIGN_IN -> "/auth/m2s/email/login"
    PolarisOperation.DEVICES -> "/dms/queryDeviceListByUserId"
    PolarisOperation.STATUS -> "/dms/queryDeviceStatus"
    PolarisOperation.READINGS -> "/dms/queryDeviceRealTimeData"
    PolarisOperation.REFRESH_SESSION -> ""
}
