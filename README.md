This project provides a program to reproduce https://github.com/scylladb/scylladb/issues/17232.

```bash
./gradlew run
```

should eventually print something like

```
scylla: seastar/src/core/fstream.cc:230: virtual seastar::file_data_source_impl::~file_data_source_impl(): Assertion `_reads_in_progress == 0' failed.
Aborting on shard 1.
Backtrace:
  0x35b6bab
  0x35e6bef
  linux-vdso.so.1+0x79f
  /opt/scylladb/libreloc/libc.so.6+0x90a6f
  /opt/scylladb/libreloc/libc.so.6+0x44b3f
  /opt/scylladb/libreloc/libc.so.6+0x30287
  /opt/scylladb/libreloc/libc.so.6+0x3db93
  /opt/scylladb/libreloc/libc.so.6+0x3dc0f
  0x357c9bf
  0x357c9d3
  0x193cb5f
  0x193ca83
  0x19489ff
  0x19a43af
  0x19ccd2b
  0x15f4fd7
  0x35c56e7
  0x35c65ef
  0x35e70bf
  0x359c2c7
  /opt/scylladb/libreloc/libc.so.6+0x8edb3
  /opt/scylladb/libreloc/libc.so.6+0xfd55b
```
