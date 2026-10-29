/**
 * A proof made by the game's CertificateProof.java (with a throwaway "Mojang" key and the vanilla
 * ProfilePublicKey.Data#signedPayload layout), so the Worker is checked against Java's bytes.
 * Regenerate if the proof format changes on either side.
 */
export const JAVA_PROOF_VECTOR = {
  "mojangKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAsSzkVSLcirBrRE75hLxqmpEKMnNRuc3l/BFmet6A1CBwI51NGvm4SWJBIe0IeN1UFAV4c//XAd3Qbx9/r1FUwwnqOyok1H0eW2/9qzLveguBY1TeveCUsOp6obryioyRyRCLXYcUQbd5fEV5jGgQVCJyoc79mtvJIxhApFauKp6y7CHWwbyIutVMELNq2Bhm5E0OjRlnpYnJOx4k6wC8AmqjRtODBOHk1c/GcbG21keNRvTkYa8PXeT6qqsBAr0w2fmnfGPhA1We46uZkEVozQMmkqOooA3/XQyM7VLC5VsFzpJoMMx09ugf13oP5h8/wb2MTs0Wuv1gWmTo61KIUwIDAQAB",
  "serverId": "0123456789abcdef0123456789abcdef01234567",
  "X-OpenDoors-Profile": "01234567-89ab-cdef-0123-456789abcdef",
  "X-OpenDoors-Key": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkv2LX+hI1ThYa6zIfdpYGUbm9fb+A7l4Ean1BxU3CCTKmh/B/Iw2MBA4dEfgo7OYZ5g9j9wW6YIT18DqPGOSlHk70/bWpg2+ed8wRahDsuXEXTujZElsz5WU2UmEgYJdSy4QGg0mF9JmZrOuHCLinQeMt4z/3HJz6hru5LEwE8GYgVLL+I+OOPUbcBzT05QKVH6ii/yn1a47pWteD5z0JQ8BECZ222JVdUz3tY+9pf7YKNoY8ItKorV3VBMaU0R9D3AZTe6QCNebkXynkOQ7cDEofKedjq1qNn+gUqXUi5mli0edDN3HiJJKxyl5+UqbdkyHw10XZH+C/f7WnQfZSwIDAQAB",
  "X-OpenDoors-Key-Expiry": "4102444800000",
  "X-OpenDoors-Key-Signature": "PLBCcGneGP+0C4uGZq0mC3A4IwEuKv53xJYv9+HKq1Cq5RgiIggg3+8gfBRH3PqXS+xVEwpxCSgur/A7Xwm/NYcMswxaxwAq3o5jvJz1lawjJqtmWTKIPlmZLygiutpWVAxoLASwi39llbBxZ3Sv2kutBferExDwo8asg2l7YtylncbwGqTIIn+woY3uaHydnV/5R7Tha3hY981827HM64n6XiY+SliDMOCW/gXwXDa24YIlIDoK5N3YTnmSZcXFHlZkfkCjM1LS+YaYIETEtn9lFqbzJlyPc3D+HbsVAV1ZVWUZjPRhPPQvVbvAmM03Mc8XNlQ+Jgu5oHPfdwziZA==",
  "X-OpenDoors-Signature": "K3N/kecFadQfb3MrIitHTnSFFz+oiZtLkwyHjh7hyqjDikyk4QD1V7NEKyc3ZEEUpK4CPXg7VKgurwSCHhy6N34af2lroRl8AEDJ+on9i1kwSVljlLwKeEM8LhlKJypyiUefbsfaZ9LXXRpyENbY0p0uZoO9SE9MnvUVXEkKLI+m6ug9TdcnHYDhHKX5e6WEQEGwY/O+YyUlIi1VdHgZqk5mwGV0M75WEARZ2syfDRJg3h68mATmbkgHBnhxv7oCza3MRaERmkVHHm6rWjM97pwA6eJTQdX7w5aita1aXsDU2qXkmh1z4S9Qlq5cf0ag4i9Q/ZBjiURz3HZ/W/tJfw=="
} as const;
