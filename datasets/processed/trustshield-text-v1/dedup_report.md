# TrustShield dataset preparation report

Near-duplicate threshold selected: 0.85
TF-IDF character n-grams: analyzer=char, range=3-5, sublinear_tf=true; sparse block neighbour search.
Template signatures mask digits, URLs, amounts/currency and capitalised entity-like tokens.

## Near-duplicate transparency

| cosine threshold | pairs |
|---:|---:|
| 0.70 | 272 |
| 0.80 | 150 |
| 0.85 | 108 |
| 0.90 | 62 |

Union-find groups: 4040
Cross-source near-duplicate pairs at selected threshold: 0
Template signatures: 4131

## Exact/normalized dedup

Accepted after schema validation: 8760
Accepted after caps: 4360
Survivors: 4168
Removed ids: 192
Cross-label conflicts dropped: 1

## Under-supported categories

Counts are before category-model merging.

- `credential_theft`: train=35, validation=6, test=20 (under-supported)
- `digital_arrest`: train=33, validation=7, test=20 (under-supported)
- `investment_scam`: train=35, validation=6, test=22 (under-supported)

## Removed ids

- `indiaspam-12e20a2a`
- `indiaspam-12e20a2a`
- `indiaspam-1b1624be`
- `indiaspam-1c107e39`
- `indiaspam-2abc9405`
- `indiaspam-2abc9405`
- `indiaspam-2caab43d`
- `indiaspam-2d1dd2ec`
- `indiaspam-35a95df8`
- `indiaspam-36869135`
- `indiaspam-4406d90a`
- `indiaspam-45c83ce6`
- `indiaspam-467de3d0`
- `indiaspam-4f29cead`
- `indiaspam-5382d5f3`
- `indiaspam-543675b9`
- `indiaspam-5826e455`
- `indiaspam-5b4116e6`
- `indiaspam-5d55faa7`
- `indiaspam-611930ed`
- `indiaspam-621bb38c`
- `indiaspam-64ff8c95`
- `indiaspam-6ac54454`
- `indiaspam-6cc324c1`
- `indiaspam-6f98057d`
- `indiaspam-6fabc2e4`
- `indiaspam-7997ad9e`
- `indiaspam-79bf5b96`
- `indiaspam-7b455d9c`
- `indiaspam-7da2aa90`
- `indiaspam-810671cd`
- `indiaspam-82c1544b`
- `indiaspam-8575ed78`
- `indiaspam-8915370c`
- `indiaspam-89851d5c`
- `indiaspam-8c76abde`
- `indiaspam-8cc482d8`
- `indiaspam-8cfb62e5`
- `indiaspam-914db1b4`
- `indiaspam-92846054`
- `indiaspam-92f9cd3d`
- `indiaspam-953ca774`
- `indiaspam-95d37935`
- `indiaspam-96c3007e`
- `indiaspam-9892ce29`
- `indiaspam-9892ce29`
- `indiaspam-9b022694`
- `indiaspam-9b243a93`
- `indiaspam-9f8efc44`
- `indiaspam-9fc39512`
- `indiaspam-a32b8cf0`
- `indiaspam-a3e97196`
- `indiaspam-a74f3956`
- `indiaspam-a74f3956`
- `indiaspam-a7fdcbf0`
- `indiaspam-a8d9e0d1`
- `indiaspam-a932d53a`
- `indiaspam-ab797089`
- `indiaspam-ac7fd8fe`
- `indiaspam-ad52883b`
- `indiaspam-ae0aa950`
- `indiaspam-b1c0fe1c`
- `indiaspam-b8bbb166`
- `indiaspam-bf414412`
- `indiaspam-bfc26878`
- `indiaspam-c31dc9f7`
- `indiaspam-c68cf171`
- `indiaspam-cec88074`
- `indiaspam-cf4eb6bc`
- `indiaspam-d1c7e90f`
- `indiaspam-d474e816`
- `indiaspam-d4836553`
- `indiaspam-d4836553`
- `indiaspam-d94fecc7`
- `indiaspam-da51c6e0`
- `indiaspam-dce9a927`
- `indiaspam-e0f34c8c`
- `indiaspam-e696661c`
- `indiaspam-e979c3fa`
- `indiaspam-e9d30080`
- `indiaspam-ea101f27`
- `indiaspam-ea101f27`
- `indiaspam-ea101f27`
- `indiaspam-ec9ae13d`
- `indiaspam-ee546d0d`
- `indiaspam-ee9b2888`
- `indiaspam-ee9b2888`
- `indiaspam-ee9b2888`
- `indiaspam-f04c60ad`
- `indiaspam-fac3ca45`
- `indiaspam-fb941320`
- `indiaspam-ff5e741d`
- `ucisms-054654cc`
- `ucisms-063fcb3b`
- `ucisms-07840e71`
- `ucisms-0d8c8e32`
- `ucisms-1131b6e4`
- `ucisms-17ff66ab`
- `ucisms-1895cef8`
- `ucisms-19540868`
- `ucisms-196675a2`
- `ucisms-1e0ae39c`
- `ucisms-1f9d9c51`
- `ucisms-218fb133`
- `ucisms-21930c88`
- `ucisms-22c38837`
- `ucisms-2a2d31bd`
- `ucisms-2aac42eb`
- `ucisms-2b3fe6cc`
- `ucisms-2d296534`
- `ucisms-2dacccab`
- `ucisms-338bf66e`
- `ucisms-36e48452`
- `ucisms-37cda9aa`
- `ucisms-39651400`
- `ucisms-39ea60e6`
- `ucisms-3abb689b`
- `ucisms-3bf48fa4`
- `ucisms-3f9f162d`
- `ucisms-44b59431`
- `ucisms-44d06641`
- `ucisms-4eeb5e2c`
- `ucisms-502a6ee2`
- `ucisms-504fc5a2`
- `ucisms-510d0f3c`
- `ucisms-518f4c82`
- `ucisms-5d2fb195`
- `ucisms-5d2fb195`
- `ucisms-5f18d6f4`
- `ucisms-61d46104`
- `ucisms-61d46104`
- `ucisms-61d46104`
- `ucisms-6788fe49`
- `ucisms-67ff5112`
- `ucisms-6a6e6932`
- `ucisms-6a6e6932`
- `ucisms-728aa879`
- `ucisms-74b23e0e`
- `ucisms-7ce09ec4`
- `ucisms-7d18f9ef`
- `ucisms-7d61a8f5`
- `ucisms-7e9cadbf`
- `ucisms-84db5741`
- `ucisms-88ef2e91`
- `ucisms-93579e6b`
- `ucisms-94213d46`
- `ucisms-98aed353`
- `ucisms-98aed353`
- `ucisms-9a749d46`
- `ucisms-9e772678`
- `ucisms-a3517d13`
- `ucisms-a3c185ff`
- `ucisms-a97b5761`
- `ucisms-aa669dc9`
- `ucisms-aa8526b4`
- `ucisms-aec3cc5d`
- `ucisms-af7bd375`
- `ucisms-b0265aa2`
- `ucisms-b4282f4b`
- `ucisms-b4282f4b`
- `ucisms-b9441349`
- `ucisms-b9fc255a`
- `ucisms-ba7c6913`
- `ucisms-c523e292`
- `ucisms-ca243cfd`
- `ucisms-ca243cfd`
- `ucisms-cea6ebef`
- `ucisms-d1243c28`
- `ucisms-d1243c28`
- `ucisms-d2409d94`
- `ucisms-d4c07817`
- `ucisms-d5aacec0`
- `ucisms-d5ec6057`
- `ucisms-d968f60b`
- `ucisms-d97266bb`
- `ucisms-dc18ce5e`
- `ucisms-dc4c1558`
- `ucisms-dd6eadd8`
- `ucisms-dd6eadd8`
- `ucisms-ddbab175`
- `ucisms-e02f54be`
- `ucisms-e325db77`
- `ucisms-e4a21055`
- `ucisms-e9e9c36d`
- `ucisms-e9f3b26e`
- `ucisms-ecabb870`
- `ucisms-ecabb870`
- `ucisms-f0105196`
- `ucisms-f3518a9f`
- `ucisms-f79030ff`
- `ucisms-f79030ff`
- `ucisms-fcb8bea9`

## Cross-label conflicts

- `private! your 0000 account statement for 00000000000 shows 000 un-redeemed s. i. m. points. call 00000000000 identifier code: 00000 expires 00/00/00`: ucisms-1131b6e4, ucisms-37cda9aa, ucisms-3abb689b
