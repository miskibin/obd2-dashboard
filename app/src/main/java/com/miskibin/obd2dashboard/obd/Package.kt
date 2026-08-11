/**
 * OBD-II protocol layer.
 *
 * Owns everything that is transport-independent: ELM327 AT command handling, PID
 * definitions, request framing and response parsing into typed readings.
 * Nothing here should know about Bluetooth.
 */
package com.miskibin.obd2dashboard.obd
