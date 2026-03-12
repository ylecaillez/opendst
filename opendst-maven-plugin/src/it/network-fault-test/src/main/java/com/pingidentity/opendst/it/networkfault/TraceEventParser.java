/*
 * Copyright 2026 Ping Identity Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pingidentity.opendst.it.networkfault;

import generatedOutput.pobserve.PEvents;
import generatedOutput.pobserve.PTypes;
import pobserve.runtime.events.PEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps {@link TraceEvents} typed records to PObserve {@link PEvent} objects.
 * <p>
 * Socket identity strings (e.g. {@code "server:0.0.0.0:8080#3"}) are mapped
 * to unique {@code long} machine IDs, as required by the PObserve-generated
 * code where the P {@code machine} type compiles to {@code long}.
 */
final class TraceEventParser {

    private final Map<String, Long> socketIdMap = new HashMap<>();
    private long nextMachineId = 1;

    /**
     * Converts a typed trace event to a PObserve event.
     *
     * @return the PObserve event, or {@code null} for {@link TraceEvents.TestCompleted}
     *         (handled separately by the auditor)
     */
    PEvent<?> toPObserveEvent(TraceEvents.TraceEvent event) {
        return switch (event) {
            case TraceEvents.ConnectionEstablished e ->
                    new PEvents.eSpec_ConnectionEstablished(
                            new PTypes.PTuple_clnts_srvrs_accpt(
                                    machineId(e.clientSocket()),
                                    machineId(e.serverSocket()),
                                    machineId(e.acceptedSocket())));
            case TraceEvents.SocketConnected e ->
                    new PEvents.eSpec_SocketConnected(
                            new PTypes.PTuple_sckt(
                                    machineId(e.socket())));
            case TraceEvents.SocketAccepted e ->
                    new PEvents.eSpec_SocketAccepted(
                            new PTypes.PTuple_sckt(
                                    machineId(e.socket())));
            case TraceEvents.DataWritten e ->
                    new PEvents.eSpec_DataWritten(
                            new PTypes.PTuple_sckt_dat(
                                    machineId(e.socket()),
                                    bytesToLongList(e.data())));
            case TraceEvents.DataRead e ->
                    new PEvents.eSpec_DataRead(
                            new PTypes.PTuple_sckt_dat(
                                    machineId(e.socket()),
                                    bytesToLongList(e.data())));
            case TraceEvents.SocketClosed e ->
                    new PEvents.eSpec_SocketClosed(
                            new PTypes.PTuple_sckt(machineId(e.socket())));
            case TraceEvents.IOExceptionRaised e ->
                    new PEvents.eSpec_IOExceptionRaised(
                            new PTypes.PTuple_sckt_oprtn(
                                    machineId(e.socket()),
                                    e.operation()));
            case TraceEvents.ShutdownOutputCompleted e ->
                    new PEvents.eSpec_ShutdownOutputCompleted(
                            new PTypes.PTuple_sckt(machineId(e.socket())));
            case TraceEvents.EOFRead e ->
                    new PEvents.eSpec_EOFRead(
                            new PTypes.PTuple_sckt(machineId(e.socket())));
            case TraceEvents.ConnectionReset e ->
                    new PEvents.eSpec_ConnectionReset(
                            new PTypes.PTuple_sckt(machineId(e.socket())));
            case TraceEvents.ShutdownInputCompleted e ->
                    new PEvents.eSpec_ShutdownInputCompleted(
                            new PTypes.PTuple_sckt(
                                    machineId(e.socket())));
            case TraceEvents.DataDiscarded e ->
                    new PEvents.eSpec_DataDiscarded(
                            new PTypes.PTuple_sckt_bytcn(
                                    machineId(e.socket()),
                                    e.byteCount()));
            case TraceEvents.AvailableQueried e ->
                    new PEvents.eSpec_AvailableQueried(
                            new PTypes.PTuple_sckt_rprtd(
                                    machineId(e.socket()),
                                    e.reportedCount()));
            case TraceEvents.TestCompleted ignored -> null;
        };
    }

    private long machineId(String socketId) {
        return socketIdMap.computeIfAbsent(socketId, k -> nextMachineId++);
    }

    private static ArrayList<Long> bytesToLongList(byte[] bytes) {
        var list = new ArrayList<Long>(bytes.length);
        for (byte b : bytes) {
            list.add((long) (b & 0xFF));
        }
        return list;
    }
}
