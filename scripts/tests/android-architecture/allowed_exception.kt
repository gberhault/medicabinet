package com.medicabinet.core

import java.time.Instant

object SystemReader { fun now(): Instant = Instant.now() } // arch-allow:core_no_wall_clock one explained exception
