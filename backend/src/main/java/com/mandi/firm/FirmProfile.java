package com.mandi.firm;

/** What the printed bill shows about the firm. Name is fixed at creation; the other fields are optional text the owner may edit. */
public record FirmProfile(String name, String nameHi, String address, String addressHi, String phone, String licenceNo,
                          String shopNo) {
}
