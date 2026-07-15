# Third-party notices

## QAuxiliary

Project: `cinit/QAuxiliary`

Repository: https://github.com/cinit/QAuxiliary

The QQ NT recall command names, recall message type/subtype values, relevant protobuf field observations, and the decision to separate Java push parsing from native-kernel interception were researched with reference to QAuxiliary's public implementation.

QAuxiliary declares GNU AGPLv3 licensing together with its project EULA. This repository's v3.0 implementation does not copy QAuxiliary's full `RevokeMsgHook`, native ARM64 inline-hook implementation, UI framework, DexKit framework, or generated protobuf classes. It uses an independently written minimal protobuf wire reader and a Java-level `onMsfPush` interception implementation.

Any future direct code reuse or native implementation port must be reviewed separately for full license and EULA compliance.
