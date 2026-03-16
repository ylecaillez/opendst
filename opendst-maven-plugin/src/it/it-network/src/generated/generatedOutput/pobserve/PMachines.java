package generatedOutput.pobserve;

/***************************************************************************
 * This file was auto-generated on Saturday, 14 March 2026 at 18:40:29.
 * Please do not edit manually!
 **************************************************************************/

import java.io.Serializable;
import java.util.*;
import java.util.logging.*;

public class PMachines {
    private static Logger logger = Logger.getLogger(PMachines.class.getName());
    static { logger.setLevel(Level.OFF); };
    public static class DataIntegrity extends pobserve.runtime.Monitor<DataIntegrity.PrtStates> implements Serializable {
        
        public static class Supplier implements java.util.function.Supplier<DataIntegrity>, Serializable {
            public DataIntegrity get() {
                DataIntegrity ret = new DataIntegrity();
                ret.ready();
                return ret;
            }
        }
        
        private HashMap<Long, Long> peerOf = new HashMap<Long, Long>();
        public HashMap<Long, Long> get_peerOf() { return this.peerOf; };
        
        private HashMap<Long, ArrayList<Long>> written = new HashMap<Long, ArrayList<Long>>();
        public HashMap<Long, ArrayList<Long>> get_written() { return this.written; };
        
        private HashMap<Long, ArrayList<Long>> readData = new HashMap<Long, ArrayList<Long>>();
        public HashMap<Long, ArrayList<Long>> get_readData() { return this.readData; };
        
        
        public enum PrtStates {
            Monitoring
        }
        
        public DataIntegrity() {
            super();
            addState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_1)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_2)
                .build());
        } // constructor
        
        public void reInitializeMonitor() {
            registerState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_1)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_2)
                .build());
        }
        
        public java.util.List<Class<? extends pobserve.runtime.events.PEvent<?>>> getEventTypes() {
            return java.util.Arrays.asList(PEvents.eSpec_ConnectionEstablished.class, PEvents.eSpec_DataRead.class, PEvents.eSpec_DataWritten.class);
        }
        
        private void Anon(PTypes.PTuple_clnts_srvrs_accpt payload) {
            long TMP_tmp0;
            long TMP_tmp1;
            long TMP_tmp2;
            long TMP_tmp3;
            long TMP_tmp4;
            long TMP_tmp5;
            long TMP_tmp6;
            ArrayList<Long> TMP_tmp7;
            long TMP_tmp8;
            ArrayList<Long> TMP_tmp9;
            long TMP_tmp10;
            ArrayList<Long> TMP_tmp11;
            long TMP_tmp12;
            ArrayList<Long> TMP_tmp13;
            
            TMP_tmp0 = payload.clientSocket;
            TMP_tmp1 = payload.acceptedSocket;
            TMP_tmp2 = TMP_tmp1;
            peerOf.put(TMP_tmp0,TMP_tmp2);
            TMP_tmp3 = payload.acceptedSocket;
            TMP_tmp4 = payload.clientSocket;
            TMP_tmp5 = TMP_tmp4;
            peerOf.put(TMP_tmp3,TMP_tmp5);
            TMP_tmp6 = payload.clientSocket;
            TMP_tmp7 = new ArrayList<Long>();
            written.put(TMP_tmp6,TMP_tmp7);
            TMP_tmp8 = payload.acceptedSocket;
            TMP_tmp9 = new ArrayList<Long>();
            written.put(TMP_tmp8,TMP_tmp9);
            TMP_tmp10 = payload.clientSocket;
            TMP_tmp11 = new ArrayList<Long>();
            readData.put(TMP_tmp10,TMP_tmp11);
            TMP_tmp12 = payload.acceptedSocket;
            TMP_tmp13 = new ArrayList<Long>();
            readData.put(TMP_tmp12,TMP_tmp13);
        }
        private void Anon_1(PTypes.PTuple_sckt_dat payload_1) {
            long i = 0L;
            long TMP_tmp0_1;
            boolean TMP_tmp1_1;
            ArrayList<Long> TMP_tmp2_1;
            long TMP_tmp3_1;
            boolean TMP_tmp4_1;
            boolean TMP_tmp5_1;
            long TMP_tmp6_1;
            long TMP_tmp7_1;
            ArrayList<Long> TMP_tmp8_1;
            long TMP_tmp9_1;
            ArrayList<Long> TMP_tmp10_1;
            long TMP_tmp11_1;
            long TMP_tmp12_1;
            
            TMP_tmp0_1 = payload_1.socket;
            TMP_tmp1_1 = written.containsKey(TMP_tmp0_1);
            if (TMP_tmp1_1) {
                i = 0L;
                while ((true)) {
                    TMP_tmp2_1 = payload_1.dat;
                    TMP_tmp3_1 = TMP_tmp2_1.size();
                    TMP_tmp4_1 = i < TMP_tmp3_1;
                    TMP_tmp5_1 = TMP_tmp4_1;
                    if (TMP_tmp5_1) {
                    }
                    else
                    {
                        break;
                    }
                    TMP_tmp6_1 = payload_1.socket;
                    TMP_tmp7_1 = payload_1.socket;
                    TMP_tmp8_1 = written.get(TMP_tmp7_1);
                    TMP_tmp9_1 = TMP_tmp8_1.size();
                    TMP_tmp10_1 = payload_1.dat;
                    TMP_tmp11_1 = TMP_tmp10_1.get((int)(i));
                    written.get(TMP_tmp6_1).add((int)(TMP_tmp9_1), TMP_tmp11_1);
                    TMP_tmp12_1 = i + 1L;
                    i = TMP_tmp12_1;
                }
            }
        }
        private void Anon_2(PTypes.PTuple_sckt_dat payload_2) {
            long peer = 0L;
            long i_1 = 0L;
            long readOffset = 0L;
            long TMP_tmp0_2;
            boolean TMP_tmp1_2;
            long TMP_tmp2_2;
            boolean TMP_tmp3_2;
            boolean TMP_tmp4_2;
            long TMP_tmp5_2;
            long TMP_tmp6_2;
            long TMP_tmp7_2;
            ArrayList<Long> TMP_tmp8_2;
            long TMP_tmp9_2;
            boolean TMP_tmp10_2;
            boolean TMP_tmp11_2;
            long TMP_tmp12_2;
            long TMP_tmp13_1;
            ArrayList<Long> TMP_tmp14;
            long TMP_tmp15;
            ArrayList<Long> TMP_tmp16;
            long TMP_tmp17;
            long TMP_tmp18;
            long TMP_tmp19;
            ArrayList<Long> TMP_tmp20;
            long TMP_tmp21;
            ArrayList<Long> TMP_tmp22;
            long TMP_tmp23;
            boolean TMP_tmp24;
            String TMP_tmp25;
            String TMP_tmp26;
            String TMP_tmp27;
            long TMP_tmp28;
            ArrayList<Long> TMP_tmp29;
            long TMP_tmp30;
            ArrayList<Long> TMP_tmp31;
            long TMP_tmp32;
            long TMP_tmp33;
            long TMP_tmp34;
            ArrayList<Long> TMP_tmp35;
            long TMP_tmp36;
            boolean TMP_tmp37;
            boolean TMP_tmp38;
            long TMP_tmp39;
            ArrayList<Long> TMP_tmp40;
            long TMP_tmp41;
            ArrayList<Long> TMP_tmp42;
            long TMP_tmp43;
            boolean TMP_tmp44;
            String TMP_tmp45;
            String TMP_tmp46;
            String TMP_tmp47;
            long TMP_tmp48;
            
            TMP_tmp0_2 = payload_2.socket;
            TMP_tmp1_2 = readData.containsKey(TMP_tmp0_2);
            TMP_tmp4_2 = TMP_tmp1_2;
            if (TMP_tmp4_2) {
                TMP_tmp2_2 = payload_2.socket;
                TMP_tmp3_2 = peerOf.containsKey(TMP_tmp2_2);
                TMP_tmp4_2 = TMP_tmp3_2;
            }
            if (TMP_tmp4_2) {
                TMP_tmp5_2 = payload_2.socket;
                TMP_tmp6_2 = peerOf.get(TMP_tmp5_2);
                TMP_tmp7_2 = TMP_tmp6_2;
                peer = TMP_tmp7_2;
                i_1 = 0L;
                while ((true)) {
                    TMP_tmp8_2 = payload_2.dat;
                    TMP_tmp9_2 = TMP_tmp8_2.size();
                    TMP_tmp10_2 = i_1 < TMP_tmp9_2;
                    TMP_tmp11_2 = TMP_tmp10_2;
                    if (TMP_tmp11_2) {
                    }
                    else
                    {
                        break;
                    }
                    TMP_tmp12_2 = payload_2.socket;
                    TMP_tmp13_1 = payload_2.socket;
                    TMP_tmp14 = readData.get(TMP_tmp13_1);
                    TMP_tmp15 = TMP_tmp14.size();
                    TMP_tmp16 = payload_2.dat;
                    TMP_tmp17 = TMP_tmp16.get((int)(i_1));
                    readData.get(TMP_tmp12_2).add((int)(TMP_tmp15), TMP_tmp17);
                    TMP_tmp18 = i_1 + 1L;
                    i_1 = TMP_tmp18;
                }
                TMP_tmp19 = payload_2.socket;
                TMP_tmp20 = readData.get(TMP_tmp19);
                TMP_tmp21 = TMP_tmp20.size();
                readOffset = TMP_tmp21;
                TMP_tmp22 = written.get(peer);
                TMP_tmp23 = TMP_tmp22.size();
                TMP_tmp24 = readOffset <= TMP_tmp23;
                if (TMP_tmp24) {
                }
                else
                {
                    TMP_tmp25 = "Specifications.p:76:17";
                    TMP_tmp26 = "DataIntegrity violation: socket read more bytes than peer has written";
                    TMP_tmp27 = java.text.MessageFormat.format("{0} {1}", TMP_tmp25, TMP_tmp26);
                    tryAssert(TMP_tmp24, TMP_tmp27);
                }
                TMP_tmp28 = payload_2.socket;
                TMP_tmp29 = readData.get(TMP_tmp28);
                TMP_tmp30 = TMP_tmp29.size();
                TMP_tmp31 = payload_2.dat;
                TMP_tmp32 = TMP_tmp31.size();
                TMP_tmp33 = TMP_tmp30 - TMP_tmp32;
                i_1 = TMP_tmp33;
                while ((true)) {
                    TMP_tmp34 = payload_2.socket;
                    TMP_tmp35 = readData.get(TMP_tmp34);
                    TMP_tmp36 = TMP_tmp35.size();
                    TMP_tmp37 = i_1 < TMP_tmp36;
                    TMP_tmp38 = TMP_tmp37;
                    if (TMP_tmp38) {
                    }
                    else
                    {
                        break;
                    }
                    TMP_tmp39 = payload_2.socket;
                    TMP_tmp40 = readData.get(TMP_tmp39);
                    TMP_tmp41 = TMP_tmp40.get((int)(i_1));
                    TMP_tmp42 = written.get(peer);
                    TMP_tmp43 = TMP_tmp42.get((int)(i_1));
                    TMP_tmp44 = TMP_tmp41 == TMP_tmp43;
                    if (TMP_tmp44) {
                    }
                    else
                    {
                        TMP_tmp45 = "Specifications.p:82:21";
                        TMP_tmp46 = "DataIntegrity violation: byte mismatch between written and read data";
                        TMP_tmp47 = java.text.MessageFormat.format("{0} {1}", TMP_tmp45, TMP_tmp46);
                        tryAssert(TMP_tmp44, TMP_tmp47);
                    }
                    TMP_tmp48 = i_1 + 1L;
                    i_1 = TMP_tmp48;
                }
            }
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder("DataIntegrity");
            sb.append("[");
            sb.append("peerOf=" + peerOf);
            sb.append(", written=" + written);
            sb.append(", readData=" + readData);
            sb.append("]");
            return sb.toString();
        } // toString()
        
        public boolean deepEquals(DataIntegrity other) {
            return (true
                && pobserve.runtime.values.Equality.deepEquals(this.peerOf, other.peerOf)
                && pobserve.runtime.values.Equality.deepEquals(this.written, other.written)
                && pobserve.runtime.values.Equality.deepEquals(this.readData, other.readData)
            );
        } // deepEquals()
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass()) && this.deepEquals((DataIntegrity)other);
        } // equals()
        
        public int hashCode() {
            return Objects.hash(peerOf, written, readData);
        } // hashCode()
        
    } // DataIntegrity monitor definition
    public static class NoWriteAfterClose extends pobserve.runtime.Monitor<NoWriteAfterClose.PrtStates> implements Serializable {
        
        public static class Supplier implements java.util.function.Supplier<NoWriteAfterClose>, Serializable {
            public NoWriteAfterClose get() {
                NoWriteAfterClose ret = new NoWriteAfterClose();
                ret.ready();
                return ret;
            }
        }
        
        private LinkedHashSet<Long> closedSockets = new LinkedHashSet<Long>();
        public LinkedHashSet<Long> get_closedSockets() { return this.closedSockets; };
        
        
        public enum PrtStates {
            Monitoring_1
        }
        
        public NoWriteAfterClose() {
            super();
            addState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_1)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_3)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_4)
                .build());
        } // constructor
        
        public void reInitializeMonitor() {
            registerState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_1)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_3)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_4)
                .build());
        }
        
        public java.util.List<Class<? extends pobserve.runtime.events.PEvent<?>>> getEventTypes() {
            return java.util.Arrays.asList(PEvents.eSpec_DataWritten.class, PEvents.eSpec_SocketClosed.class);
        }
        
        private void Anon_3(PTypes.PTuple_sckt payload_3) {
            long TMP_tmp0_3;
            
            TMP_tmp0_3 = payload_3.socket;
            closedSockets.add(TMP_tmp0_3);
        }
        private void Anon_4(PTypes.PTuple_sckt_dat payload_4) {
            long TMP_tmp0_4;
            boolean TMP_tmp1_3;
            boolean TMP_tmp2_3;
            String TMP_tmp3_3;
            String TMP_tmp4_3;
            String TMP_tmp5_3;
            
            TMP_tmp0_4 = payload_4.socket;
            TMP_tmp1_3 = closedSockets.contains(TMP_tmp0_4);
            TMP_tmp2_3 = !(TMP_tmp1_3);
            if (TMP_tmp2_3) {
            }
            else
            {
                TMP_tmp3_3 = "Specifications.p:107:13";
                TMP_tmp4_3 = "NoWriteAfterClose violation: data written on a closed socket";
                TMP_tmp5_3 = java.text.MessageFormat.format("{0} {1}", TMP_tmp3_3, TMP_tmp4_3);
                tryAssert(TMP_tmp2_3, TMP_tmp5_3);
            }
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder("NoWriteAfterClose");
            sb.append("[");
            sb.append("closedSockets=" + closedSockets);
            sb.append("]");
            return sb.toString();
        } // toString()
        
        public boolean deepEquals(NoWriteAfterClose other) {
            return (true
                && pobserve.runtime.values.Equality.deepEquals(this.closedSockets, other.closedSockets)
            );
        } // deepEquals()
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass()) && this.deepEquals((NoWriteAfterClose)other);
        } // equals()
        
        public int hashCode() {
            return Objects.hash(closedSockets);
        } // hashCode()
        
    } // NoWriteAfterClose monitor definition
    public static class NoPhantomData extends pobserve.runtime.Monitor<NoPhantomData.PrtStates> implements Serializable {
        
        public static class Supplier implements java.util.function.Supplier<NoPhantomData>, Serializable {
            public NoPhantomData get() {
                NoPhantomData ret = new NoPhantomData();
                ret.ready();
                return ret;
            }
        }
        
        private LinkedHashSet<Long> connectedSockets = new LinkedHashSet<Long>();
        public LinkedHashSet<Long> get_connectedSockets() { return this.connectedSockets; };
        
        
        public enum PrtStates {
            Monitoring_2
        }
        
        public NoPhantomData() {
            super();
            addState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_2)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_5)
                .withEvent(PEvents.eSpec_SocketConnected.class, this::Anon_6)
                .withEvent(PEvents.eSpec_SocketAccepted.class, this::Anon_7)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_8)
                .build());
        } // constructor
        
        public void reInitializeMonitor() {
            registerState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_2)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_5)
                .withEvent(PEvents.eSpec_SocketConnected.class, this::Anon_6)
                .withEvent(PEvents.eSpec_SocketAccepted.class, this::Anon_7)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_8)
                .build());
        }
        
        public java.util.List<Class<? extends pobserve.runtime.events.PEvent<?>>> getEventTypes() {
            return java.util.Arrays.asList(PEvents.eSpec_ConnectionEstablished.class, PEvents.eSpec_DataRead.class, PEvents.eSpec_SocketAccepted.class, PEvents.eSpec_SocketConnected.class);
        }
        
        private void Anon_5(PTypes.PTuple_clnts_srvrs_accpt payload_5) {
            long TMP_tmp0_5;
            long TMP_tmp1_4;
            
            TMP_tmp0_5 = payload_5.clientSocket;
            connectedSockets.add(TMP_tmp0_5);
            TMP_tmp1_4 = payload_5.acceptedSocket;
            connectedSockets.add(TMP_tmp1_4);
        }
        private void Anon_6(PTypes.PTuple_sckt payload_6) {
            long TMP_tmp0_6;
            
            TMP_tmp0_6 = payload_6.socket;
            connectedSockets.add(TMP_tmp0_6);
        }
        private void Anon_7(PTypes.PTuple_sckt payload_7) {
            long TMP_tmp0_7;
            
            TMP_tmp0_7 = payload_7.socket;
            connectedSockets.add(TMP_tmp0_7);
        }
        private void Anon_8(PTypes.PTuple_sckt_dat payload_8) {
            long TMP_tmp0_8;
            boolean TMP_tmp1_5;
            String TMP_tmp2_4;
            String TMP_tmp3_4;
            String TMP_tmp4_4;
            
            TMP_tmp0_8 = payload_8.socket;
            TMP_tmp1_5 = connectedSockets.contains(TMP_tmp0_8);
            if (TMP_tmp1_5) {
            }
            else
            {
                TMP_tmp2_4 = "Specifications.p:138:13";
                TMP_tmp3_4 = "NoPhantomData violation: data read from a socket that was never connected";
                TMP_tmp4_4 = java.text.MessageFormat.format("{0} {1}", TMP_tmp2_4, TMP_tmp3_4);
                tryAssert(TMP_tmp1_5, TMP_tmp4_4);
            }
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder("NoPhantomData");
            sb.append("[");
            sb.append("connectedSockets=" + connectedSockets);
            sb.append("]");
            return sb.toString();
        } // toString()
        
        public boolean deepEquals(NoPhantomData other) {
            return (true
                && pobserve.runtime.values.Equality.deepEquals(this.connectedSockets, other.connectedSockets)
            );
        } // deepEquals()
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass()) && this.deepEquals((NoPhantomData)other);
        } // equals()
        
        public int hashCode() {
            return Objects.hash(connectedSockets);
        } // hashCode()
        
    } // NoPhantomData monitor definition
    public static class IOExceptionOnClosedSocket extends pobserve.runtime.Monitor<IOExceptionOnClosedSocket.PrtStates> implements Serializable {
        
        public static class Supplier implements java.util.function.Supplier<IOExceptionOnClosedSocket>, Serializable {
            public IOExceptionOnClosedSocket get() {
                IOExceptionOnClosedSocket ret = new IOExceptionOnClosedSocket();
                ret.ready();
                return ret;
            }
        }
        
        private LinkedHashSet<Long> closedSockets_1 = new LinkedHashSet<Long>();
        public LinkedHashSet<Long> get_closedSockets_1() { return this.closedSockets_1; };
        
        
        public enum PrtStates {
            Monitoring_3
        }
        
        public IOExceptionOnClosedSocket() {
            super();
            addState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_3)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_9)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, __ -> { ; })
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_10)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_11)
                .build());
        } // constructor
        
        public void reInitializeMonitor() {
            registerState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_3)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_9)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, __ -> { ; })
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_10)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_11)
                .build());
        }
        
        public java.util.List<Class<? extends pobserve.runtime.events.PEvent<?>>> getEventTypes() {
            return java.util.Arrays.asList(PEvents.eSpec_DataRead.class, PEvents.eSpec_DataWritten.class, PEvents.eSpec_IOExceptionRaised.class, PEvents.eSpec_SocketClosed.class);
        }
        
        private void Anon_9(PTypes.PTuple_sckt payload_9) {
            long TMP_tmp0_9;
            
            TMP_tmp0_9 = payload_9.socket;
            closedSockets_1.add(TMP_tmp0_9);
        }
        private void Anon_10(PTypes.PTuple_sckt_dat payload_10) {
            long TMP_tmp0_10;
            boolean TMP_tmp1_6;
            boolean TMP_tmp2_5;
            String TMP_tmp3_5;
            String TMP_tmp4_5;
            String TMP_tmp5_4;
            
            TMP_tmp0_10 = payload_10.socket;
            TMP_tmp1_6 = closedSockets_1.contains(TMP_tmp0_10);
            TMP_tmp2_5 = !(TMP_tmp1_6);
            if (TMP_tmp2_5) {
            }
            else
            {
                TMP_tmp3_5 = "Specifications.p:169:13";
                TMP_tmp4_5 = "IOExceptionOnClosedSocket: successful write on closed socket";
                TMP_tmp5_4 = java.text.MessageFormat.format("{0} {1}", TMP_tmp3_5, TMP_tmp4_5);
                tryAssert(TMP_tmp2_5, TMP_tmp5_4);
            }
        }
        private void Anon_11(PTypes.PTuple_sckt_dat payload_11) {
            long TMP_tmp0_11;
            boolean TMP_tmp1_7;
            boolean TMP_tmp2_6;
            String TMP_tmp3_6;
            String TMP_tmp4_6;
            String TMP_tmp5_5;
            
            TMP_tmp0_11 = payload_11.socket;
            TMP_tmp1_7 = closedSockets_1.contains(TMP_tmp0_11);
            TMP_tmp2_6 = !(TMP_tmp1_7);
            if (TMP_tmp2_6) {
            }
            else
            {
                TMP_tmp3_6 = "Specifications.p:175:13";
                TMP_tmp4_6 = "IOExceptionOnClosedSocket: successful read on closed socket";
                TMP_tmp5_5 = java.text.MessageFormat.format("{0} {1}", TMP_tmp3_6, TMP_tmp4_6);
                tryAssert(TMP_tmp2_6, TMP_tmp5_5);
            }
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder("IOExceptionOnClosedSocket");
            sb.append("[");
            sb.append("closedSockets=" + closedSockets_1);
            sb.append("]");
            return sb.toString();
        } // toString()
        
        public boolean deepEquals(IOExceptionOnClosedSocket other) {
            return (true
                && pobserve.runtime.values.Equality.deepEquals(this.closedSockets_1, other.closedSockets_1)
            );
        } // deepEquals()
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass()) && this.deepEquals((IOExceptionOnClosedSocket)other);
        } // equals()
        
        public int hashCode() {
            return Objects.hash(closedSockets_1);
        } // hashCode()
        
    } // IOExceptionOnClosedSocket monitor definition
    public static class NoWriteAfterShutdownOutput extends pobserve.runtime.Monitor<NoWriteAfterShutdownOutput.PrtStates> implements Serializable {
        
        public static class Supplier implements java.util.function.Supplier<NoWriteAfterShutdownOutput>, Serializable {
            public NoWriteAfterShutdownOutput get() {
                NoWriteAfterShutdownOutput ret = new NoWriteAfterShutdownOutput();
                ret.ready();
                return ret;
            }
        }
        
        private LinkedHashSet<Long> shutdownSockets = new LinkedHashSet<Long>();
        public LinkedHashSet<Long> get_shutdownSockets() { return this.shutdownSockets; };
        
        
        public enum PrtStates {
            Monitoring_4
        }
        
        public NoWriteAfterShutdownOutput() {
            super();
            addState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_4)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ShutdownOutputCompleted.class, this::Anon_12)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_13)
                .build());
        } // constructor
        
        public void reInitializeMonitor() {
            registerState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_4)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ShutdownOutputCompleted.class, this::Anon_12)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_13)
                .build());
        }
        
        public java.util.List<Class<? extends pobserve.runtime.events.PEvent<?>>> getEventTypes() {
            return java.util.Arrays.asList(PEvents.eSpec_DataWritten.class, PEvents.eSpec_ShutdownOutputCompleted.class);
        }
        
        private void Anon_12(PTypes.PTuple_sckt payload_12) {
            long TMP_tmp0_12;
            
            TMP_tmp0_12 = payload_12.socket;
            shutdownSockets.add(TMP_tmp0_12);
        }
        private void Anon_13(PTypes.PTuple_sckt_dat payload_13) {
            long TMP_tmp0_13;
            boolean TMP_tmp1_8;
            boolean TMP_tmp2_7;
            String TMP_tmp3_7;
            String TMP_tmp4_7;
            String TMP_tmp5_6;
            
            TMP_tmp0_13 = payload_13.socket;
            TMP_tmp1_8 = shutdownSockets.contains(TMP_tmp0_13);
            TMP_tmp2_7 = !(TMP_tmp1_8);
            if (TMP_tmp2_7) {
            }
            else
            {
                TMP_tmp3_7 = "Specifications.p:198:13";
                TMP_tmp4_7 = "NoWriteAfterShutdownOutput violation: data written after shutdownOutput()";
                TMP_tmp5_6 = java.text.MessageFormat.format("{0} {1}", TMP_tmp3_7, TMP_tmp4_7);
                tryAssert(TMP_tmp2_7, TMP_tmp5_6);
            }
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder("NoWriteAfterShutdownOutput");
            sb.append("[");
            sb.append("shutdownSockets=" + shutdownSockets);
            sb.append("]");
            return sb.toString();
        } // toString()
        
        public boolean deepEquals(NoWriteAfterShutdownOutput other) {
            return (true
                && pobserve.runtime.values.Equality.deepEquals(this.shutdownSockets, other.shutdownSockets)
            );
        } // deepEquals()
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass()) && this.deepEquals((NoWriteAfterShutdownOutput)other);
        } // equals()
        
        public int hashCode() {
            return Objects.hash(shutdownSockets);
        } // hashCode()
        
    } // NoWriteAfterShutdownOutput monitor definition
    public static class NoReadAfterShutdownInput extends pobserve.runtime.Monitor<NoReadAfterShutdownInput.PrtStates> implements Serializable {
        
        public static class Supplier implements java.util.function.Supplier<NoReadAfterShutdownInput>, Serializable {
            public NoReadAfterShutdownInput get() {
                NoReadAfterShutdownInput ret = new NoReadAfterShutdownInput();
                ret.ready();
                return ret;
            }
        }
        
        private LinkedHashSet<Long> shutdownSockets_1 = new LinkedHashSet<Long>();
        public LinkedHashSet<Long> get_shutdownSockets_1() { return this.shutdownSockets_1; };
        
        
        public enum PrtStates {
            Monitoring_5
        }
        
        public NoReadAfterShutdownInput() {
            super();
            addState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_5)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ShutdownInputCompleted.class, this::Anon_14)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_15)
                .build());
        } // constructor
        
        public void reInitializeMonitor() {
            registerState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_5)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ShutdownInputCompleted.class, this::Anon_14)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_15)
                .build());
        }
        
        public java.util.List<Class<? extends pobserve.runtime.events.PEvent<?>>> getEventTypes() {
            return java.util.Arrays.asList(PEvents.eSpec_DataRead.class, PEvents.eSpec_ShutdownInputCompleted.class);
        }
        
        private void Anon_14(PTypes.PTuple_sckt payload_14) {
            long TMP_tmp0_14;
            
            TMP_tmp0_14 = payload_14.socket;
            shutdownSockets_1.add(TMP_tmp0_14);
        }
        private void Anon_15(PTypes.PTuple_sckt_dat payload_15) {
            long TMP_tmp0_15;
            boolean TMP_tmp1_9;
            boolean TMP_tmp2_8;
            String TMP_tmp3_8;
            String TMP_tmp4_8;
            String TMP_tmp5_7;
            
            TMP_tmp0_15 = payload_15.socket;
            TMP_tmp1_9 = shutdownSockets_1.contains(TMP_tmp0_15);
            TMP_tmp2_8 = !(TMP_tmp1_9);
            if (TMP_tmp2_8) {
            }
            else
            {
                TMP_tmp3_8 = "Specifications.p:221:13";
                TMP_tmp4_8 = "NoReadAfterShutdownInput violation: data read after shutdownInput()";
                TMP_tmp5_7 = java.text.MessageFormat.format("{0} {1}", TMP_tmp3_8, TMP_tmp4_8);
                tryAssert(TMP_tmp2_8, TMP_tmp5_7);
            }
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder("NoReadAfterShutdownInput");
            sb.append("[");
            sb.append("shutdownSockets=" + shutdownSockets_1);
            sb.append("]");
            return sb.toString();
        } // toString()
        
        public boolean deepEquals(NoReadAfterShutdownInput other) {
            return (true
                && pobserve.runtime.values.Equality.deepEquals(this.shutdownSockets_1, other.shutdownSockets_1)
            );
        } // deepEquals()
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass()) && this.deepEquals((NoReadAfterShutdownInput)other);
        } // equals()
        
        public int hashCode() {
            return Objects.hash(shutdownSockets_1);
        } // hashCode()
        
    } // NoReadAfterShutdownInput monitor definition
    public static class AvailableConsistency extends pobserve.runtime.Monitor<AvailableConsistency.PrtStates> implements Serializable {
        
        public static class Supplier implements java.util.function.Supplier<AvailableConsistency>, Serializable {
            public AvailableConsistency get() {
                AvailableConsistency ret = new AvailableConsistency();
                ret.ready();
                return ret;
            }
        }
        
        private HashMap<Long, Long> peerOf_1 = new HashMap<Long, Long>();
        public HashMap<Long, Long> get_peerOf_1() { return this.peerOf_1; };
        
        private HashMap<Long, Long> totalWritten = new HashMap<Long, Long>();
        public HashMap<Long, Long> get_totalWritten() { return this.totalWritten; };
        
        private HashMap<Long, Long> totalRead = new HashMap<Long, Long>();
        public HashMap<Long, Long> get_totalRead() { return this.totalRead; };
        
        private LinkedHashSet<Long> inputShutdown = new LinkedHashSet<Long>();
        public LinkedHashSet<Long> get_inputShutdown() { return this.inputShutdown; };
        
        
        public enum PrtStates {
            Monitoring_6
        }
        
        public AvailableConsistency() {
            super();
            addState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_6)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_16)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_17)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_18)
                .withEvent(PEvents.eSpec_ShutdownInputCompleted.class, this::Anon_19)
                .withEvent(PEvents.eSpec_AvailableQueried.class, this::Anon_20)
                .build());
        } // constructor
        
        public void reInitializeMonitor() {
            registerState(pobserve.runtime.State.keyedOn(PrtStates.Monitoring_6)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_16)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_17)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_18)
                .withEvent(PEvents.eSpec_ShutdownInputCompleted.class, this::Anon_19)
                .withEvent(PEvents.eSpec_AvailableQueried.class, this::Anon_20)
                .build());
        }
        
        public java.util.List<Class<? extends pobserve.runtime.events.PEvent<?>>> getEventTypes() {
            return java.util.Arrays.asList(PEvents.eSpec_AvailableQueried.class, PEvents.eSpec_ConnectionEstablished.class, PEvents.eSpec_DataRead.class, PEvents.eSpec_DataWritten.class, PEvents.eSpec_ShutdownInputCompleted.class);
        }
        
        private void Anon_16(PTypes.PTuple_clnts_srvrs_accpt payload_16) {
            long TMP_tmp0_16;
            long TMP_tmp1_10;
            long TMP_tmp2_9;
            long TMP_tmp3_9;
            long TMP_tmp4_9;
            long TMP_tmp5_8;
            long TMP_tmp6_3;
            long TMP_tmp7_3;
            long TMP_tmp8_3;
            long TMP_tmp9_3;
            
            TMP_tmp0_16 = payload_16.clientSocket;
            TMP_tmp1_10 = payload_16.acceptedSocket;
            TMP_tmp2_9 = TMP_tmp1_10;
            peerOf_1.put(TMP_tmp0_16,TMP_tmp2_9);
            TMP_tmp3_9 = payload_16.acceptedSocket;
            TMP_tmp4_9 = payload_16.clientSocket;
            TMP_tmp5_8 = TMP_tmp4_9;
            peerOf_1.put(TMP_tmp3_9,TMP_tmp5_8);
            TMP_tmp6_3 = payload_16.clientSocket;
            totalWritten.put(TMP_tmp6_3,0L);
            TMP_tmp7_3 = payload_16.acceptedSocket;
            totalWritten.put(TMP_tmp7_3,0L);
            TMP_tmp8_3 = payload_16.clientSocket;
            totalRead.put(TMP_tmp8_3,0L);
            TMP_tmp9_3 = payload_16.acceptedSocket;
            totalRead.put(TMP_tmp9_3,0L);
        }
        private void Anon_17(PTypes.PTuple_sckt_dat payload_17) {
            long TMP_tmp0_17;
            boolean TMP_tmp1_11;
            long TMP_tmp2_10;
            long TMP_tmp3_10;
            long TMP_tmp4_10;
            ArrayList<Long> TMP_tmp5_9;
            long TMP_tmp6_4;
            long TMP_tmp7_4;
            
            TMP_tmp0_17 = payload_17.socket;
            TMP_tmp1_11 = totalWritten.containsKey(TMP_tmp0_17);
            if (TMP_tmp1_11) {
                TMP_tmp2_10 = payload_17.socket;
                TMP_tmp3_10 = payload_17.socket;
                TMP_tmp4_10 = totalWritten.get(TMP_tmp3_10);
                TMP_tmp5_9 = payload_17.dat;
                TMP_tmp6_4 = TMP_tmp5_9.size();
                TMP_tmp7_4 = TMP_tmp4_10 + TMP_tmp6_4;
                totalWritten.put(TMP_tmp2_10,TMP_tmp7_4);
            }
        }
        private void Anon_18(PTypes.PTuple_sckt_dat payload_18) {
            long TMP_tmp0_18;
            boolean TMP_tmp1_12;
            long TMP_tmp2_11;
            long TMP_tmp3_11;
            long TMP_tmp4_11;
            ArrayList<Long> TMP_tmp5_10;
            long TMP_tmp6_5;
            long TMP_tmp7_5;
            
            TMP_tmp0_18 = payload_18.socket;
            TMP_tmp1_12 = totalRead.containsKey(TMP_tmp0_18);
            if (TMP_tmp1_12) {
                TMP_tmp2_11 = payload_18.socket;
                TMP_tmp3_11 = payload_18.socket;
                TMP_tmp4_11 = totalRead.get(TMP_tmp3_11);
                TMP_tmp5_10 = payload_18.dat;
                TMP_tmp6_5 = TMP_tmp5_10.size();
                TMP_tmp7_5 = TMP_tmp4_11 + TMP_tmp6_5;
                totalRead.put(TMP_tmp2_11,TMP_tmp7_5);
            }
        }
        private void Anon_19(PTypes.PTuple_sckt payload_19) {
            long TMP_tmp0_19;
            
            TMP_tmp0_19 = payload_19.socket;
            inputShutdown.add(TMP_tmp0_19);
        }
        private void Anon_20(PTypes.PTuple_sckt_rprtd payload_20) {
            long TMP_tmp0_20;
            boolean TMP_tmp1_13;
            long TMP_tmp2_12;
            boolean TMP_tmp3_12;
            String TMP_tmp4_12;
            String TMP_tmp5_11;
            String TMP_tmp6_6;
            long TMP_tmp7_6;
            long TMP_tmp8_4;
            
            TMP_tmp0_20 = payload_20.socket;
            TMP_tmp1_13 = inputShutdown.contains(TMP_tmp0_20);
            if (TMP_tmp1_13) {
                TMP_tmp2_12 = payload_20.reportedCount;
                TMP_tmp3_12 = TMP_tmp2_12 == 0L;
                if (TMP_tmp3_12) {
                }
                else
                {
                    TMP_tmp4_12 = "Specifications.p:277:17";
                    TMP_tmp5_11 = "AvailableConsistency violation: available() non-zero after shutdownInput()";
                    TMP_tmp6_6 = java.text.MessageFormat.format("{0} {1}", TMP_tmp4_12, TMP_tmp5_11);
                    tryAssert(TMP_tmp3_12, TMP_tmp6_6);
                }
            }
            TMP_tmp7_6 = payload_20.socket;
            TMP_tmp8_4 = payload_20.reportedCount;
            CheckAvailableUpperBound(TMP_tmp7_6, TMP_tmp8_4);
        }
        private void CheckAvailableUpperBound(long socket,long reportedCount) {
            long peer_1 = 0L;
            long unreadBytes = 0L;
            boolean TMP_tmp0_21;
            boolean TMP_tmp1_14;
            boolean TMP_tmp2_13;
            long TMP_tmp3_13;
            long TMP_tmp4_13;
            boolean TMP_tmp5_12;
            long TMP_tmp6_7;
            long TMP_tmp7_7;
            long TMP_tmp8_5;
            boolean TMP_tmp9_4;
            String TMP_tmp10_3;
            String TMP_tmp11_3;
            String TMP_tmp12_3;
            
            TMP_tmp0_21 = peerOf_1.containsKey(socket);
            TMP_tmp2_13 = TMP_tmp0_21;
            if (TMP_tmp2_13) {
                TMP_tmp1_14 = totalRead.containsKey(socket);
                TMP_tmp2_13 = TMP_tmp1_14;
            }
            if (TMP_tmp2_13) {
                TMP_tmp3_13 = peerOf_1.get(socket);
                TMP_tmp4_13 = TMP_tmp3_13;
                peer_1 = TMP_tmp4_13;
                TMP_tmp5_12 = totalWritten.containsKey(peer_1);
                if (TMP_tmp5_12) {
                    TMP_tmp6_7 = totalWritten.get(peer_1);
                    TMP_tmp7_7 = totalRead.get(socket);
                    TMP_tmp8_5 = TMP_tmp6_7 - TMP_tmp7_7;
                    unreadBytes = TMP_tmp8_5;
                    TMP_tmp9_4 = reportedCount <= unreadBytes;
                    if (TMP_tmp9_4) {
                    }
                    else
                    {
                        TMP_tmp10_3 = "Specifications.p:293:17";
                        TMP_tmp11_3 = "AvailableConsistency violation: available() reports more bytes than peer has written minus what we read";
                        TMP_tmp12_3 = java.text.MessageFormat.format("{0} {1}", TMP_tmp10_3, TMP_tmp11_3);
                        tryAssert(TMP_tmp9_4, TMP_tmp12_3);
                    }
                }
            }
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder("AvailableConsistency");
            sb.append("[");
            sb.append("peerOf=" + peerOf_1);
            sb.append(", totalWritten=" + totalWritten);
            sb.append(", totalRead=" + totalRead);
            sb.append(", inputShutdown=" + inputShutdown);
            sb.append("]");
            return sb.toString();
        } // toString()
        
        public boolean deepEquals(AvailableConsistency other) {
            return (true
                && pobserve.runtime.values.Equality.deepEquals(this.peerOf_1, other.peerOf_1)
                && pobserve.runtime.values.Equality.deepEquals(this.totalWritten, other.totalWritten)
                && pobserve.runtime.values.Equality.deepEquals(this.totalRead, other.totalRead)
                && pobserve.runtime.values.Equality.deepEquals(this.inputShutdown, other.inputShutdown)
            );
        } // deepEquals()
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass()) && this.deepEquals((AvailableConsistency)other);
        } // equals()
        
        public int hashCode() {
            return Objects.hash(peerOf_1, totalWritten, totalRead, inputShutdown);
        } // hashCode()
        
    } // AvailableConsistency monitor definition
    public static class GracefulShutdownIntegrity extends pobserve.runtime.Monitor<GracefulShutdownIntegrity.PrtStates> implements Serializable {
        
        public static class Supplier implements java.util.function.Supplier<GracefulShutdownIntegrity>, Serializable {
            public GracefulShutdownIntegrity get() {
                GracefulShutdownIntegrity ret = new GracefulShutdownIntegrity();
                ret.ready();
                return ret;
            }
        }
        
        private HashMap<Long, Long> peerOf_2 = new HashMap<Long, Long>();
        public HashMap<Long, Long> get_peerOf_2() { return this.peerOf_2; };
        
        private HashMap<Long, Long> totalWritten_1 = new HashMap<Long, Long>();
        public HashMap<Long, Long> get_totalWritten_1() { return this.totalWritten_1; };
        
        private HashMap<Long, Long> totalRead_1 = new HashMap<Long, Long>();
        public HashMap<Long, Long> get_totalRead_1() { return this.totalRead_1; };
        
        private LinkedHashSet<Long> shutdownOutputSockets = new LinkedHashSet<Long>();
        public LinkedHashSet<Long> get_shutdownOutputSockets() { return this.shutdownOutputSockets; };
        
        private LinkedHashSet<Long> excused = new LinkedHashSet<Long>();
        public LinkedHashSet<Long> get_excused() { return this.excused; };
        
        
        public enum PrtStates {
            NoObligation,
            PendingGracefulDelivery
        }
        
        public GracefulShutdownIntegrity() {
            super();
            addState(pobserve.runtime.State.keyedOn(PrtStates.NoObligation)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_21)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_22)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_23)
                .withEvent(PEvents.eSpec_ShutdownOutputCompleted.class, this::Anon_24)
                .withEvent(PEvents.eSpec_EOFRead.class, this::Anon_25)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_26)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_27)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_28)
                .build());
            addState(pobserve.runtime.State.keyedOn(PrtStates.PendingGracefulDelivery)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_29)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_30)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_31)
                .withEvent(PEvents.eSpec_ShutdownOutputCompleted.class, this::Anon_32)
                .withEvent(PEvents.eSpec_EOFRead.class, this::Anon_33)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_34)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_35)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_36)
                .build());
        } // constructor
        
        public void reInitializeMonitor() {
            registerState(pobserve.runtime.State.keyedOn(PrtStates.NoObligation)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_21)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_22)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_23)
                .withEvent(PEvents.eSpec_ShutdownOutputCompleted.class, this::Anon_24)
                .withEvent(PEvents.eSpec_EOFRead.class, this::Anon_25)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_26)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_27)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_28)
                .build());
            registerState(pobserve.runtime.State.keyedOn(PrtStates.PendingGracefulDelivery)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_29)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_30)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_31)
                .withEvent(PEvents.eSpec_ShutdownOutputCompleted.class, this::Anon_32)
                .withEvent(PEvents.eSpec_EOFRead.class, this::Anon_33)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_34)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_35)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_36)
                .build());
        }
        
        public java.util.List<Class<? extends pobserve.runtime.events.PEvent<?>>> getEventTypes() {
            return java.util.Arrays.asList(PEvents.eSpec_ConnectionEstablished.class, PEvents.eSpec_ConnectionReset.class, PEvents.eSpec_DataRead.class, PEvents.eSpec_DataWritten.class, PEvents.eSpec_EOFRead.class, PEvents.eSpec_IOExceptionRaised.class, PEvents.eSpec_ShutdownOutputCompleted.class, PEvents.eSpec_SocketClosed.class);
        }
        
        private void Anon_21(PTypes.PTuple_clnts_srvrs_accpt payload_21) {
            long TMP_tmp0_22;
            long TMP_tmp1_15;
            long TMP_tmp2_14;
            long TMP_tmp3_14;
            long TMP_tmp4_14;
            long TMP_tmp5_13;
            long TMP_tmp6_8;
            long TMP_tmp7_8;
            long TMP_tmp8_6;
            long TMP_tmp9_5;
            
            TMP_tmp0_22 = payload_21.clientSocket;
            TMP_tmp1_15 = payload_21.acceptedSocket;
            TMP_tmp2_14 = TMP_tmp1_15;
            peerOf_2.put(TMP_tmp0_22,TMP_tmp2_14);
            TMP_tmp3_14 = payload_21.acceptedSocket;
            TMP_tmp4_14 = payload_21.clientSocket;
            TMP_tmp5_13 = TMP_tmp4_14;
            peerOf_2.put(TMP_tmp3_14,TMP_tmp5_13);
            TMP_tmp6_8 = payload_21.clientSocket;
            totalWritten_1.put(TMP_tmp6_8,0L);
            TMP_tmp7_8 = payload_21.acceptedSocket;
            totalWritten_1.put(TMP_tmp7_8,0L);
            TMP_tmp8_6 = payload_21.clientSocket;
            totalRead_1.put(TMP_tmp8_6,0L);
            TMP_tmp9_5 = payload_21.acceptedSocket;
            totalRead_1.put(TMP_tmp9_5,0L);
        }
        private void Anon_22(PTypes.PTuple_sckt_dat payload_22) {
            long TMP_tmp0_23;
            boolean TMP_tmp1_16;
            long TMP_tmp2_15;
            long TMP_tmp3_15;
            long TMP_tmp4_15;
            ArrayList<Long> TMP_tmp5_14;
            long TMP_tmp6_9;
            long TMP_tmp7_9;
            
            TMP_tmp0_23 = payload_22.socket;
            TMP_tmp1_16 = totalWritten_1.containsKey(TMP_tmp0_23);
            if (TMP_tmp1_16) {
                TMP_tmp2_15 = payload_22.socket;
                TMP_tmp3_15 = payload_22.socket;
                TMP_tmp4_15 = totalWritten_1.get(TMP_tmp3_15);
                TMP_tmp5_14 = payload_22.dat;
                TMP_tmp6_9 = TMP_tmp5_14.size();
                TMP_tmp7_9 = TMP_tmp4_15 + TMP_tmp6_9;
                totalWritten_1.put(TMP_tmp2_15,TMP_tmp7_9);
            }
        }
        private void Anon_23(PTypes.PTuple_sckt_dat payload_23) {
            long TMP_tmp0_24;
            boolean TMP_tmp1_17;
            long TMP_tmp2_16;
            long TMP_tmp3_16;
            long TMP_tmp4_16;
            ArrayList<Long> TMP_tmp5_15;
            long TMP_tmp6_10;
            long TMP_tmp7_10;
            
            TMP_tmp0_24 = payload_23.socket;
            TMP_tmp1_17 = totalRead_1.containsKey(TMP_tmp0_24);
            if (TMP_tmp1_17) {
                TMP_tmp2_16 = payload_23.socket;
                TMP_tmp3_16 = payload_23.socket;
                TMP_tmp4_16 = totalRead_1.get(TMP_tmp3_16);
                TMP_tmp5_15 = payload_23.dat;
                TMP_tmp6_10 = TMP_tmp5_15.size();
                TMP_tmp7_10 = TMP_tmp4_16 + TMP_tmp6_10;
                totalRead_1.put(TMP_tmp2_16,TMP_tmp7_10);
            }
        }
        private void Anon_24(PTypes.PTuple_sckt payload_24) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_25;
            boolean TMP_tmp1_18;
            long TMP_tmp2_17;
            boolean TMP_tmp3_17;
            boolean TMP_tmp4_17;
            boolean TMP_tmp5_16;
            long TMP_tmp6_11;
            boolean TMP_tmp7_11;
            
            TMP_tmp0_25 = payload_24.socket;
            TMP_tmp1_18 = peerOf_2.containsKey(TMP_tmp0_25);
            TMP_tmp5_16 = TMP_tmp1_18;
            if (TMP_tmp5_16) {
                TMP_tmp2_17 = payload_24.socket;
                TMP_tmp3_17 = excused.contains(TMP_tmp2_17);
                TMP_tmp4_17 = !(TMP_tmp3_17);
                TMP_tmp5_16 = TMP_tmp4_17;
            }
            if (TMP_tmp5_16) {
                TMP_tmp6_11 = payload_24.socket;
                shutdownOutputSockets.add(TMP_tmp6_11);
                TMP_tmp7_11 = HasPendingObligation();
                if (TMP_tmp7_11) {
                    gotoState(PrtStates.PendingGracefulDelivery);
                    return;
                }
            }
        }
        private void Anon_25(PTypes.PTuple_sckt payload_25) {
            long TMP_tmp0_26;
            boolean TMP_tmp1_19;
            long TMP_tmp2_18;
            long TMP_tmp3_18;
            
            TMP_tmp0_26 = payload_25.socket;
            TMP_tmp1_19 = peerOf_2.containsKey(TMP_tmp0_26);
            if (TMP_tmp1_19) {
                TMP_tmp2_18 = payload_25.socket;
                TMP_tmp3_18 = peerOf_2.get(TMP_tmp2_18);
                shutdownOutputSockets.remove(TMP_tmp3_18);
            }
        }
        private void Anon_26(PTypes.PTuple_sckt payload_26) {
            long TMP_tmp0_27;
            
            TMP_tmp0_27 = payload_26.socket;
            ExcusePeerObligation(TMP_tmp0_27);
        }
        private void Anon_27(PTypes.PTuple_sckt payload_27) {
            long TMP_tmp0_28;
            
            TMP_tmp0_28 = payload_27.socket;
            ExcuseBothSides(TMP_tmp0_28);
        }
        private void Anon_28(PTypes.PTuple_sckt_oprtn payload_28) {
            String TMP_tmp0_29;
            String TMP_tmp1_20;
            boolean TMP_tmp2_19;
            long TMP_tmp3_19;
            
            TMP_tmp0_29 = payload_28.operation;
            TMP_tmp1_20 = "close";
            TMP_tmp2_19 = (pobserve.runtime.values.Equality.deepEquals(TMP_tmp0_29, TMP_tmp1_20) == true);
            if (TMP_tmp2_19) {
                TMP_tmp3_19 = payload_28.socket;
                ExcusePeerObligation(TMP_tmp3_19);
            }
        }
        private void Anon_29(PTypes.PTuple_clnts_srvrs_accpt payload_29) {
            long TMP_tmp0_30;
            long TMP_tmp1_21;
            long TMP_tmp2_20;
            long TMP_tmp3_20;
            long TMP_tmp4_18;
            long TMP_tmp5_17;
            long TMP_tmp6_12;
            long TMP_tmp7_12;
            long TMP_tmp8_7;
            long TMP_tmp9_6;
            
            TMP_tmp0_30 = payload_29.clientSocket;
            TMP_tmp1_21 = payload_29.acceptedSocket;
            TMP_tmp2_20 = TMP_tmp1_21;
            peerOf_2.put(TMP_tmp0_30,TMP_tmp2_20);
            TMP_tmp3_20 = payload_29.acceptedSocket;
            TMP_tmp4_18 = payload_29.clientSocket;
            TMP_tmp5_17 = TMP_tmp4_18;
            peerOf_2.put(TMP_tmp3_20,TMP_tmp5_17);
            TMP_tmp6_12 = payload_29.clientSocket;
            totalWritten_1.put(TMP_tmp6_12,0L);
            TMP_tmp7_12 = payload_29.acceptedSocket;
            totalWritten_1.put(TMP_tmp7_12,0L);
            TMP_tmp8_7 = payload_29.clientSocket;
            totalRead_1.put(TMP_tmp8_7,0L);
            TMP_tmp9_6 = payload_29.acceptedSocket;
            totalRead_1.put(TMP_tmp9_6,0L);
        }
        private void Anon_30(PTypes.PTuple_sckt_dat payload_30) {
            long TMP_tmp0_31;
            boolean TMP_tmp1_22;
            long TMP_tmp2_21;
            long TMP_tmp3_21;
            long TMP_tmp4_19;
            ArrayList<Long> TMP_tmp5_18;
            long TMP_tmp6_13;
            long TMP_tmp7_13;
            
            TMP_tmp0_31 = payload_30.socket;
            TMP_tmp1_22 = totalWritten_1.containsKey(TMP_tmp0_31);
            if (TMP_tmp1_22) {
                TMP_tmp2_21 = payload_30.socket;
                TMP_tmp3_21 = payload_30.socket;
                TMP_tmp4_19 = totalWritten_1.get(TMP_tmp3_21);
                TMP_tmp5_18 = payload_30.dat;
                TMP_tmp6_13 = TMP_tmp5_18.size();
                TMP_tmp7_13 = TMP_tmp4_19 + TMP_tmp6_13;
                totalWritten_1.put(TMP_tmp2_21,TMP_tmp7_13);
            }
        }
        private void Anon_31(PTypes.PTuple_sckt_dat payload_31) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_32;
            boolean TMP_tmp1_23;
            long TMP_tmp2_22;
            long TMP_tmp3_22;
            long TMP_tmp4_20;
            ArrayList<Long> TMP_tmp5_19;
            long TMP_tmp6_14;
            long TMP_tmp7_14;
            boolean TMP_tmp8_8;
            boolean TMP_tmp9_7;
            
            TMP_tmp0_32 = payload_31.socket;
            TMP_tmp1_23 = totalRead_1.containsKey(TMP_tmp0_32);
            if (TMP_tmp1_23) {
                TMP_tmp2_22 = payload_31.socket;
                TMP_tmp3_22 = payload_31.socket;
                TMP_tmp4_20 = totalRead_1.get(TMP_tmp3_22);
                TMP_tmp5_19 = payload_31.dat;
                TMP_tmp6_14 = TMP_tmp5_19.size();
                TMP_tmp7_14 = TMP_tmp4_20 + TMP_tmp6_14;
                totalRead_1.put(TMP_tmp2_22,TMP_tmp7_14);
            }
            TMP_tmp8_8 = HasPendingObligation();
            TMP_tmp9_7 = !(TMP_tmp8_8);
            if (TMP_tmp9_7) {
                gotoState(PrtStates.NoObligation);
                return;
            }
        }
        private void Anon_32(PTypes.PTuple_sckt payload_32) {
            long TMP_tmp0_33;
            boolean TMP_tmp1_24;
            long TMP_tmp2_23;
            boolean TMP_tmp3_23;
            boolean TMP_tmp4_21;
            boolean TMP_tmp5_20;
            long TMP_tmp6_15;
            
            TMP_tmp0_33 = payload_32.socket;
            TMP_tmp1_24 = peerOf_2.containsKey(TMP_tmp0_33);
            TMP_tmp5_20 = TMP_tmp1_24;
            if (TMP_tmp5_20) {
                TMP_tmp2_23 = payload_32.socket;
                TMP_tmp3_23 = excused.contains(TMP_tmp2_23);
                TMP_tmp4_21 = !(TMP_tmp3_23);
                TMP_tmp5_20 = TMP_tmp4_21;
            }
            if (TMP_tmp5_20) {
                TMP_tmp6_15 = payload_32.socket;
                shutdownOutputSockets.add(TMP_tmp6_15);
            }
        }
        private void Anon_33(PTypes.PTuple_sckt payload_33) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_34;
            boolean TMP_tmp1_25;
            long TMP_tmp2_24;
            long TMP_tmp3_24;
            boolean TMP_tmp4_22;
            boolean TMP_tmp5_21;
            
            TMP_tmp0_34 = payload_33.socket;
            TMP_tmp1_25 = peerOf_2.containsKey(TMP_tmp0_34);
            if (TMP_tmp1_25) {
                TMP_tmp2_24 = payload_33.socket;
                TMP_tmp3_24 = peerOf_2.get(TMP_tmp2_24);
                shutdownOutputSockets.remove(TMP_tmp3_24);
            }
            TMP_tmp4_22 = HasPendingObligation();
            TMP_tmp5_21 = !(TMP_tmp4_22);
            if (TMP_tmp5_21) {
                gotoState(PrtStates.NoObligation);
                return;
            }
        }
        private void Anon_34(PTypes.PTuple_sckt payload_34) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_35;
            boolean TMP_tmp1_26;
            boolean TMP_tmp2_25;
            
            TMP_tmp0_35 = payload_34.socket;
            ExcusePeerObligation(TMP_tmp0_35);
            TMP_tmp1_26 = HasPendingObligation();
            TMP_tmp2_25 = !(TMP_tmp1_26);
            if (TMP_tmp2_25) {
                gotoState(PrtStates.NoObligation);
                return;
            }
        }
        private void Anon_35(PTypes.PTuple_sckt payload_35) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_36;
            boolean TMP_tmp1_27;
            boolean TMP_tmp2_26;
            
            TMP_tmp0_36 = payload_35.socket;
            ExcuseBothSides(TMP_tmp0_36);
            TMP_tmp1_27 = HasPendingObligation();
            TMP_tmp2_26 = !(TMP_tmp1_27);
            if (TMP_tmp2_26) {
                gotoState(PrtStates.NoObligation);
                return;
            }
        }
        private void Anon_36(PTypes.PTuple_sckt_oprtn payload_36) throws pobserve.runtime.exceptions.TransitionException {
            String TMP_tmp0_37;
            String TMP_tmp1_28;
            boolean TMP_tmp2_27;
            long TMP_tmp3_25;
            boolean TMP_tmp4_23;
            boolean TMP_tmp5_22;
            
            TMP_tmp0_37 = payload_36.operation;
            TMP_tmp1_28 = "close";
            TMP_tmp2_27 = (pobserve.runtime.values.Equality.deepEquals(TMP_tmp0_37, TMP_tmp1_28) == true);
            if (TMP_tmp2_27) {
                TMP_tmp3_25 = payload_36.socket;
                ExcusePeerObligation(TMP_tmp3_25);
                TMP_tmp4_23 = HasPendingObligation();
                TMP_tmp5_22 = !(TMP_tmp4_23);
                if (TMP_tmp5_22) {
                    gotoState(PrtStates.NoObligation);
                    return;
                }
            }
        }
        private void ExcusePeerObligation(long socket_1) {
            long writer = 0L;
            boolean TMP_tmp0_38;
            long TMP_tmp1_29;
            long TMP_tmp2_28;
            long TMP_tmp3_26;
            
            TMP_tmp0_38 = peerOf_2.containsKey(socket_1);
            if (TMP_tmp0_38) {
                TMP_tmp1_29 = peerOf_2.get(socket_1);
                TMP_tmp2_28 = TMP_tmp1_29;
                writer = TMP_tmp2_28;
                TMP_tmp3_26 = writer;
                excused.add(TMP_tmp3_26);
                shutdownOutputSockets.remove(writer);
            }
        }
        private void ExcuseBothSides(long socket_2) {
            long TMP_tmp0_39;
            boolean TMP_tmp1_30;
            long TMP_tmp2_29;
            long TMP_tmp3_27;
            
            TMP_tmp0_39 = socket_2;
            excused.add(TMP_tmp0_39);
            shutdownOutputSockets.remove(socket_2);
            TMP_tmp1_30 = peerOf_2.containsKey(socket_2);
            if (TMP_tmp1_30) {
                TMP_tmp2_29 = peerOf_2.get(socket_2);
                excused.add(TMP_tmp2_29);
                TMP_tmp3_27 = peerOf_2.get(socket_2);
                shutdownOutputSockets.remove(TMP_tmp3_27);
            }
        }
        private boolean HasPendingObligation() {
            long sock = 0L;
            long reader = 0L;
            LinkedHashSet<Long> TMP_tmp0_40;
            long TMP_i_sock_tmp1 = 0L;
            long sizeof_sock_tmp2 = 0L;
            long TMP_tmp3_28;
            long TMP_tmp4_24;
            boolean TMP_tmp5_23;
            boolean TMP_tmp6_16;
            long TMP_tmp7_15;
            long TMP_tmp8_9;
            long TMP_tmp9_8;
            boolean TMP_tmp10_4;
            boolean TMP_tmp11_4;
            boolean TMP_tmp12_4;
            boolean TMP_tmp13_2;
            boolean TMP_tmp14_1;
            long TMP_tmp15_1;
            long TMP_tmp16_1;
            boolean TMP_tmp17_1;
            long TMP_tmp18_1;
            long TMP_tmp19_1;
            boolean TMP_tmp20_1;
            
            TMP_tmp0_40 = pobserve.runtime.values.Clone.deepClone(shutdownOutputSockets);
            TMP_i_sock_tmp1 = -1L;
            TMP_tmp3_28 = TMP_tmp0_40.size();
            sizeof_sock_tmp2 = TMP_tmp3_28;
            while ((true)) {
                TMP_tmp4_24 = sizeof_sock_tmp2 - 1L;
                TMP_tmp5_23 = TMP_i_sock_tmp1 < TMP_tmp4_24;
                TMP_tmp6_16 = TMP_tmp5_23;
                if (TMP_tmp6_16) {
                }
                else
                {
                    break;
                }
                TMP_tmp7_15 = TMP_i_sock_tmp1 + 1L;
                TMP_i_sock_tmp1 = TMP_tmp7_15;
                TMP_tmp8_9 = pobserve.runtime.values.SetIndexing.elementAt(TMP_tmp0_40, TMP_i_sock_tmp1);
                TMP_tmp9_8 = TMP_tmp8_9;
                sock = TMP_tmp9_8;
                TMP_tmp10_4 = excused.contains(sock);
                TMP_tmp11_4 = !(TMP_tmp10_4);
                if (TMP_tmp11_4) {
                    TMP_tmp12_4 = peerOf_2.containsKey(sock);
                    TMP_tmp14_1 = TMP_tmp12_4;
                    if (TMP_tmp14_1) {
                        TMP_tmp13_2 = totalWritten_1.containsKey(sock);
                        TMP_tmp14_1 = TMP_tmp13_2;
                    }
                    if (TMP_tmp14_1) {
                        TMP_tmp15_1 = peerOf_2.get(sock);
                        TMP_tmp16_1 = TMP_tmp15_1;
                        reader = TMP_tmp16_1;
                        TMP_tmp17_1 = totalRead_1.containsKey(reader);
                        if (TMP_tmp17_1) {
                            TMP_tmp18_1 = totalRead_1.get(reader);
                            TMP_tmp19_1 = totalWritten_1.get(sock);
                            TMP_tmp20_1 = TMP_tmp18_1 < TMP_tmp19_1;
                            if (TMP_tmp20_1) {
                                return (true);
                            }
                        }
                    }
                }
            }
            return (false);
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder("GracefulShutdownIntegrity");
            sb.append("[");
            sb.append("peerOf=" + peerOf_2);
            sb.append(", totalWritten=" + totalWritten_1);
            sb.append(", totalRead=" + totalRead_1);
            sb.append(", shutdownOutputSockets=" + shutdownOutputSockets);
            sb.append(", excused=" + excused);
            sb.append("]");
            return sb.toString();
        } // toString()
        
        public boolean deepEquals(GracefulShutdownIntegrity other) {
            return (true
                && pobserve.runtime.values.Equality.deepEquals(this.peerOf_2, other.peerOf_2)
                && pobserve.runtime.values.Equality.deepEquals(this.totalWritten_1, other.totalWritten_1)
                && pobserve.runtime.values.Equality.deepEquals(this.totalRead_1, other.totalRead_1)
                && pobserve.runtime.values.Equality.deepEquals(this.shutdownOutputSockets, other.shutdownOutputSockets)
                && pobserve.runtime.values.Equality.deepEquals(this.excused, other.excused)
            );
        } // deepEquals()
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass()) && this.deepEquals((GracefulShutdownIntegrity)other);
        } // equals()
        
        public int hashCode() {
            return Objects.hash(peerOf_2, totalWritten_1, totalRead_1, shutdownOutputSockets, excused);
        } // hashCode()
        
    } // GracefulShutdownIntegrity monitor definition
    public static class DeliveryLiveness extends pobserve.runtime.Monitor<DeliveryLiveness.PrtStates> implements Serializable {
        
        public static class Supplier implements java.util.function.Supplier<DeliveryLiveness>, Serializable {
            public DeliveryLiveness get() {
                DeliveryLiveness ret = new DeliveryLiveness();
                ret.ready();
                return ret;
            }
        }
        
        private HashMap<Long, Long> peerOf_3 = new HashMap<Long, Long>();
        public HashMap<Long, Long> get_peerOf_3() { return this.peerOf_3; };
        
        private HashMap<Long, Long> pendingDeliveryCount = new HashMap<Long, Long>();
        public HashMap<Long, Long> get_pendingDeliveryCount() { return this.pendingDeliveryCount; };
        
        private LinkedHashSet<Long> excused_1 = new LinkedHashSet<Long>();
        public LinkedHashSet<Long> get_excused_1() { return this.excused_1; };
        
        
        public enum PrtStates {
            NoPendingDelivery,
            PendingDelivery
        }
        
        public DeliveryLiveness() {
            super();
            addState(pobserve.runtime.State.keyedOn(PrtStates.NoPendingDelivery)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_37)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_38)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_39)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_40)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_41)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_42)
                .build());
            addState(pobserve.runtime.State.keyedOn(PrtStates.PendingDelivery)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_43)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_44)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_45)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_46)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_47)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_48)
                .build());
        } // constructor
        
        public void reInitializeMonitor() {
            registerState(pobserve.runtime.State.keyedOn(PrtStates.NoPendingDelivery)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_37)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_38)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_39)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_40)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_41)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_42)
                .build());
            registerState(pobserve.runtime.State.keyedOn(PrtStates.PendingDelivery)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_43)
                .withEvent(PEvents.eSpec_DataWritten.class, this::Anon_44)
                .withEvent(PEvents.eSpec_DataRead.class, this::Anon_45)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_46)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_47)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_48)
                .build());
        }
        
        public java.util.List<Class<? extends pobserve.runtime.events.PEvent<?>>> getEventTypes() {
            return java.util.Arrays.asList(PEvents.eSpec_ConnectionEstablished.class, PEvents.eSpec_ConnectionReset.class, PEvents.eSpec_DataRead.class, PEvents.eSpec_DataWritten.class, PEvents.eSpec_IOExceptionRaised.class, PEvents.eSpec_SocketClosed.class);
        }
        
        private void Anon_37(PTypes.PTuple_clnts_srvrs_accpt payload_37) {
            long TMP_tmp0_41;
            long TMP_tmp1_31;
            long TMP_tmp2_30;
            long TMP_tmp3_29;
            long TMP_tmp4_25;
            long TMP_tmp5_24;
            long TMP_tmp6_17;
            long TMP_tmp7_16;
            
            TMP_tmp0_41 = payload_37.clientSocket;
            TMP_tmp1_31 = payload_37.acceptedSocket;
            TMP_tmp2_30 = TMP_tmp1_31;
            peerOf_3.put(TMP_tmp0_41,TMP_tmp2_30);
            TMP_tmp3_29 = payload_37.acceptedSocket;
            TMP_tmp4_25 = payload_37.clientSocket;
            TMP_tmp5_24 = TMP_tmp4_25;
            peerOf_3.put(TMP_tmp3_29,TMP_tmp5_24);
            TMP_tmp6_17 = payload_37.clientSocket;
            pendingDeliveryCount.put(TMP_tmp6_17,0L);
            TMP_tmp7_16 = payload_37.acceptedSocket;
            pendingDeliveryCount.put(TMP_tmp7_16,0L);
        }
        private void Anon_38(PTypes.PTuple_sckt_dat payload_38) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_42;
            boolean TMP_tmp1_32;
            long TMP_tmp2_31;
            boolean TMP_tmp3_30;
            boolean TMP_tmp4_26;
            boolean TMP_tmp5_25;
            long TMP_tmp6_18;
            long TMP_tmp7_17;
            long TMP_tmp8_10;
            ArrayList<Long> TMP_tmp9_9;
            long TMP_tmp10_5;
            long TMP_tmp11_5;
            boolean TMP_tmp12_5;
            
            TMP_tmp0_42 = payload_38.socket;
            TMP_tmp1_32 = pendingDeliveryCount.containsKey(TMP_tmp0_42);
            TMP_tmp5_25 = TMP_tmp1_32;
            if (TMP_tmp5_25) {
                TMP_tmp2_31 = payload_38.socket;
                TMP_tmp3_30 = excused_1.contains(TMP_tmp2_31);
                TMP_tmp4_26 = !(TMP_tmp3_30);
                TMP_tmp5_25 = TMP_tmp4_26;
            }
            if (TMP_tmp5_25) {
                TMP_tmp6_18 = payload_38.socket;
                TMP_tmp7_17 = payload_38.socket;
                TMP_tmp8_10 = pendingDeliveryCount.get(TMP_tmp7_17);
                TMP_tmp9_9 = payload_38.dat;
                TMP_tmp10_5 = TMP_tmp9_9.size();
                TMP_tmp11_5 = TMP_tmp8_10 + TMP_tmp10_5;
                pendingDeliveryCount.put(TMP_tmp6_18,TMP_tmp11_5);
                TMP_tmp12_5 = HasPendingDelivery();
                if (TMP_tmp12_5) {
                    gotoState(PrtStates.PendingDelivery);
                    return;
                }
            }
        }
        private void Anon_39(PTypes.PTuple_sckt_dat payload_39) {
            long writer_1 = 0L;
            long TMP_tmp0_43;
            boolean TMP_tmp1_33;
            long TMP_tmp2_32;
            long TMP_tmp3_31;
            long TMP_tmp4_27;
            boolean TMP_tmp5_26;
            long TMP_tmp6_19;
            ArrayList<Long> TMP_tmp7_18;
            long TMP_tmp8_11;
            long TMP_tmp9_10;
            
            TMP_tmp0_43 = payload_39.socket;
            TMP_tmp1_33 = peerOf_3.containsKey(TMP_tmp0_43);
            if (TMP_tmp1_33) {
                TMP_tmp2_32 = payload_39.socket;
                TMP_tmp3_31 = peerOf_3.get(TMP_tmp2_32);
                TMP_tmp4_27 = TMP_tmp3_31;
                writer_1 = TMP_tmp4_27;
                TMP_tmp5_26 = pendingDeliveryCount.containsKey(writer_1);
                if (TMP_tmp5_26) {
                    TMP_tmp6_19 = pendingDeliveryCount.get(writer_1);
                    TMP_tmp7_18 = payload_39.dat;
                    TMP_tmp8_11 = TMP_tmp7_18.size();
                    TMP_tmp9_10 = TMP_tmp6_19 - TMP_tmp8_11;
                    pendingDeliveryCount.put(writer_1,TMP_tmp9_10);
                }
            }
        }
        private void Anon_40(PTypes.PTuple_sckt payload_40) {
            long TMP_tmp0_44;
            
            TMP_tmp0_44 = payload_40.socket;
            ExcusePeerObligation_1(TMP_tmp0_44);
        }
        private void Anon_41(PTypes.PTuple_sckt payload_41) {
            long TMP_tmp0_45;
            
            TMP_tmp0_45 = payload_41.socket;
            ExcuseBothSides_1(TMP_tmp0_45);
        }
        private void Anon_42(PTypes.PTuple_sckt_oprtn payload_42) {
            String TMP_tmp0_46;
            String TMP_tmp1_34;
            boolean TMP_tmp2_33;
            long TMP_tmp3_32;
            
            TMP_tmp0_46 = payload_42.operation;
            TMP_tmp1_34 = "close";
            TMP_tmp2_33 = (pobserve.runtime.values.Equality.deepEquals(TMP_tmp0_46, TMP_tmp1_34) == true);
            if (TMP_tmp2_33) {
                TMP_tmp3_32 = payload_42.socket;
                ExcusePeerObligation_1(TMP_tmp3_32);
            }
        }
        private void Anon_43(PTypes.PTuple_clnts_srvrs_accpt payload_43) {
            long TMP_tmp0_47;
            long TMP_tmp1_35;
            long TMP_tmp2_34;
            long TMP_tmp3_33;
            long TMP_tmp4_28;
            long TMP_tmp5_27;
            long TMP_tmp6_20;
            long TMP_tmp7_19;
            
            TMP_tmp0_47 = payload_43.clientSocket;
            TMP_tmp1_35 = payload_43.acceptedSocket;
            TMP_tmp2_34 = TMP_tmp1_35;
            peerOf_3.put(TMP_tmp0_47,TMP_tmp2_34);
            TMP_tmp3_33 = payload_43.acceptedSocket;
            TMP_tmp4_28 = payload_43.clientSocket;
            TMP_tmp5_27 = TMP_tmp4_28;
            peerOf_3.put(TMP_tmp3_33,TMP_tmp5_27);
            TMP_tmp6_20 = payload_43.clientSocket;
            pendingDeliveryCount.put(TMP_tmp6_20,0L);
            TMP_tmp7_19 = payload_43.acceptedSocket;
            pendingDeliveryCount.put(TMP_tmp7_19,0L);
        }
        private void Anon_44(PTypes.PTuple_sckt_dat payload_44) {
            long TMP_tmp0_48;
            boolean TMP_tmp1_36;
            long TMP_tmp2_35;
            boolean TMP_tmp3_34;
            boolean TMP_tmp4_29;
            boolean TMP_tmp5_28;
            long TMP_tmp6_21;
            long TMP_tmp7_20;
            long TMP_tmp8_12;
            ArrayList<Long> TMP_tmp9_11;
            long TMP_tmp10_6;
            long TMP_tmp11_6;
            
            TMP_tmp0_48 = payload_44.socket;
            TMP_tmp1_36 = pendingDeliveryCount.containsKey(TMP_tmp0_48);
            TMP_tmp5_28 = TMP_tmp1_36;
            if (TMP_tmp5_28) {
                TMP_tmp2_35 = payload_44.socket;
                TMP_tmp3_34 = excused_1.contains(TMP_tmp2_35);
                TMP_tmp4_29 = !(TMP_tmp3_34);
                TMP_tmp5_28 = TMP_tmp4_29;
            }
            if (TMP_tmp5_28) {
                TMP_tmp6_21 = payload_44.socket;
                TMP_tmp7_20 = payload_44.socket;
                TMP_tmp8_12 = pendingDeliveryCount.get(TMP_tmp7_20);
                TMP_tmp9_11 = payload_44.dat;
                TMP_tmp10_6 = TMP_tmp9_11.size();
                TMP_tmp11_6 = TMP_tmp8_12 + TMP_tmp10_6;
                pendingDeliveryCount.put(TMP_tmp6_21,TMP_tmp11_6);
            }
        }
        private void Anon_45(PTypes.PTuple_sckt_dat payload_45) throws pobserve.runtime.exceptions.TransitionException {
            long writer_2 = 0L;
            long TMP_tmp0_49;
            boolean TMP_tmp1_37;
            long TMP_tmp2_36;
            long TMP_tmp3_35;
            long TMP_tmp4_30;
            boolean TMP_tmp5_29;
            long TMP_tmp6_22;
            ArrayList<Long> TMP_tmp7_21;
            long TMP_tmp8_13;
            long TMP_tmp9_12;
            boolean TMP_tmp10_7;
            boolean TMP_tmp11_7;
            
            TMP_tmp0_49 = payload_45.socket;
            TMP_tmp1_37 = peerOf_3.containsKey(TMP_tmp0_49);
            if (TMP_tmp1_37) {
                TMP_tmp2_36 = payload_45.socket;
                TMP_tmp3_35 = peerOf_3.get(TMP_tmp2_36);
                TMP_tmp4_30 = TMP_tmp3_35;
                writer_2 = TMP_tmp4_30;
                TMP_tmp5_29 = pendingDeliveryCount.containsKey(writer_2);
                if (TMP_tmp5_29) {
                    TMP_tmp6_22 = pendingDeliveryCount.get(writer_2);
                    TMP_tmp7_21 = payload_45.dat;
                    TMP_tmp8_13 = TMP_tmp7_21.size();
                    TMP_tmp9_12 = TMP_tmp6_22 - TMP_tmp8_13;
                    pendingDeliveryCount.put(writer_2,TMP_tmp9_12);
                }
            }
            TMP_tmp10_7 = HasPendingDelivery();
            TMP_tmp11_7 = !(TMP_tmp10_7);
            if (TMP_tmp11_7) {
                gotoState(PrtStates.NoPendingDelivery);
                return;
            }
        }
        private void Anon_46(PTypes.PTuple_sckt payload_46) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_50;
            boolean TMP_tmp1_38;
            boolean TMP_tmp2_37;
            
            TMP_tmp0_50 = payload_46.socket;
            ExcusePeerObligation_1(TMP_tmp0_50);
            TMP_tmp1_38 = HasPendingDelivery();
            TMP_tmp2_37 = !(TMP_tmp1_38);
            if (TMP_tmp2_37) {
                gotoState(PrtStates.NoPendingDelivery);
                return;
            }
        }
        private void Anon_47(PTypes.PTuple_sckt payload_47) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_51;
            boolean TMP_tmp1_39;
            boolean TMP_tmp2_38;
            
            TMP_tmp0_51 = payload_47.socket;
            ExcuseBothSides_1(TMP_tmp0_51);
            TMP_tmp1_39 = HasPendingDelivery();
            TMP_tmp2_38 = !(TMP_tmp1_39);
            if (TMP_tmp2_38) {
                gotoState(PrtStates.NoPendingDelivery);
                return;
            }
        }
        private void Anon_48(PTypes.PTuple_sckt_oprtn payload_48) throws pobserve.runtime.exceptions.TransitionException {
            String TMP_tmp0_52;
            String TMP_tmp1_40;
            boolean TMP_tmp2_39;
            long TMP_tmp3_36;
            boolean TMP_tmp4_31;
            boolean TMP_tmp5_30;
            
            TMP_tmp0_52 = payload_48.operation;
            TMP_tmp1_40 = "close";
            TMP_tmp2_39 = (pobserve.runtime.values.Equality.deepEquals(TMP_tmp0_52, TMP_tmp1_40) == true);
            if (TMP_tmp2_39) {
                TMP_tmp3_36 = payload_48.socket;
                ExcusePeerObligation_1(TMP_tmp3_36);
                TMP_tmp4_31 = HasPendingDelivery();
                TMP_tmp5_30 = !(TMP_tmp4_31);
                if (TMP_tmp5_30) {
                    gotoState(PrtStates.NoPendingDelivery);
                    return;
                }
            }
        }
        private void ExcusePeerObligation_1(long socket_3) {
            long writer_3 = 0L;
            boolean TMP_tmp0_53;
            long TMP_tmp1_41;
            long TMP_tmp2_40;
            long TMP_tmp3_37;
            
            TMP_tmp0_53 = peerOf_3.containsKey(socket_3);
            if (TMP_tmp0_53) {
                TMP_tmp1_41 = peerOf_3.get(socket_3);
                TMP_tmp2_40 = TMP_tmp1_41;
                writer_3 = TMP_tmp2_40;
                TMP_tmp3_37 = writer_3;
                excused_1.add(TMP_tmp3_37);
            }
        }
        private void ExcuseBothSides_1(long socket_4) {
            long TMP_tmp0_54;
            boolean TMP_tmp1_42;
            long TMP_tmp2_41;
            
            TMP_tmp0_54 = socket_4;
            excused_1.add(TMP_tmp0_54);
            TMP_tmp1_42 = peerOf_3.containsKey(socket_4);
            if (TMP_tmp1_42) {
                TMP_tmp2_41 = peerOf_3.get(socket_4);
                excused_1.add(TMP_tmp2_41);
            }
        }
        private boolean HasPendingDelivery() {
            long sock_1 = 0L;
            ArrayList<Long> TMP_tmp0_55;
            long TMP_i_sock_tmp1_1 = 0L;
            long sizeof_sock_tmp2_1 = 0L;
            ArrayList<Long> TMP_tmp3_38;
            long TMP_tmp4_32;
            long TMP_tmp5_31;
            boolean TMP_tmp6_23;
            boolean TMP_tmp7_22;
            long TMP_tmp8_14;
            long TMP_tmp9_13;
            long TMP_tmp10_8;
            boolean TMP_tmp11_8;
            boolean TMP_tmp12_6;
            long TMP_tmp13_3;
            boolean TMP_tmp14_2;
            boolean TMP_tmp15_2;
            
            TMP_tmp3_38 = new ArrayList<Long>(pendingDeliveryCount.keySet());
            TMP_tmp0_55 = TMP_tmp3_38;
            TMP_i_sock_tmp1_1 = -1L;
            TMP_tmp4_32 = TMP_tmp0_55.size();
            sizeof_sock_tmp2_1 = TMP_tmp4_32;
            while ((true)) {
                TMP_tmp5_31 = sizeof_sock_tmp2_1 - 1L;
                TMP_tmp6_23 = TMP_i_sock_tmp1_1 < TMP_tmp5_31;
                TMP_tmp7_22 = TMP_tmp6_23;
                if (TMP_tmp7_22) {
                }
                else
                {
                    break;
                }
                TMP_tmp8_14 = TMP_i_sock_tmp1_1 + 1L;
                TMP_i_sock_tmp1_1 = TMP_tmp8_14;
                TMP_tmp9_13 = TMP_tmp0_55.get((int)(TMP_i_sock_tmp1_1));
                TMP_tmp10_8 = TMP_tmp9_13;
                sock_1 = TMP_tmp10_8;
                TMP_tmp11_8 = excused_1.contains(sock_1);
                TMP_tmp12_6 = !(TMP_tmp11_8);
                TMP_tmp15_2 = TMP_tmp12_6;
                if (TMP_tmp15_2) {
                    TMP_tmp13_3 = pendingDeliveryCount.get(sock_1);
                    TMP_tmp14_2 = TMP_tmp13_3 > 0L;
                    TMP_tmp15_2 = TMP_tmp14_2;
                }
                if (TMP_tmp15_2) {
                    return (true);
                }
            }
            return (false);
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder("DeliveryLiveness");
            sb.append("[");
            sb.append("peerOf=" + peerOf_3);
            sb.append(", pendingDeliveryCount=" + pendingDeliveryCount);
            sb.append(", excused=" + excused_1);
            sb.append("]");
            return sb.toString();
        } // toString()
        
        public boolean deepEquals(DeliveryLiveness other) {
            return (true
                && pobserve.runtime.values.Equality.deepEquals(this.peerOf_3, other.peerOf_3)
                && pobserve.runtime.values.Equality.deepEquals(this.pendingDeliveryCount, other.pendingDeliveryCount)
                && pobserve.runtime.values.Equality.deepEquals(this.excused_1, other.excused_1)
            );
        } // deepEquals()
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass()) && this.deepEquals((DeliveryLiveness)other);
        } // equals()
        
        public int hashCode() {
            return Objects.hash(peerOf_3, pendingDeliveryCount, excused_1);
        } // hashCode()
        
    } // DeliveryLiveness monitor definition
    public static class EOFLiveness extends pobserve.runtime.Monitor<EOFLiveness.PrtStates> implements Serializable {
        
        public static class Supplier implements java.util.function.Supplier<EOFLiveness>, Serializable {
            public EOFLiveness get() {
                EOFLiveness ret = new EOFLiveness();
                ret.ready();
                return ret;
            }
        }
        
        private HashMap<Long, Long> peerOf_4 = new HashMap<Long, Long>();
        public HashMap<Long, Long> get_peerOf_4() { return this.peerOf_4; };
        
        private LinkedHashSet<Long> pendingEOF = new LinkedHashSet<Long>();
        public LinkedHashSet<Long> get_pendingEOF() { return this.pendingEOF; };
        
        private LinkedHashSet<Long> excused_2 = new LinkedHashSet<Long>();
        public LinkedHashSet<Long> get_excused_2() { return this.excused_2; };
        
        
        public enum PrtStates {
            NoPendingEOF,
            PendingEOFDelivery
        }
        
        public EOFLiveness() {
            super();
            addState(pobserve.runtime.State.keyedOn(PrtStates.NoPendingEOF)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_49)
                .withEvent(PEvents.eSpec_ShutdownOutputCompleted.class, this::Anon_50)
                .withEvent(PEvents.eSpec_EOFRead.class, this::Anon_51)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_52)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_53)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_54)
                .build());
            addState(pobserve.runtime.State.keyedOn(PrtStates.PendingEOFDelivery)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_55)
                .withEvent(PEvents.eSpec_ShutdownOutputCompleted.class, this::Anon_56)
                .withEvent(PEvents.eSpec_EOFRead.class, this::Anon_57)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_58)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_59)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_60)
                .build());
        } // constructor
        
        public void reInitializeMonitor() {
            registerState(pobserve.runtime.State.keyedOn(PrtStates.NoPendingEOF)
                .isInitialState(true)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_49)
                .withEvent(PEvents.eSpec_ShutdownOutputCompleted.class, this::Anon_50)
                .withEvent(PEvents.eSpec_EOFRead.class, this::Anon_51)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_52)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_53)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_54)
                .build());
            registerState(pobserve.runtime.State.keyedOn(PrtStates.PendingEOFDelivery)
                .withEvent(PEvents.eSpec_ConnectionEstablished.class, this::Anon_55)
                .withEvent(PEvents.eSpec_ShutdownOutputCompleted.class, this::Anon_56)
                .withEvent(PEvents.eSpec_EOFRead.class, this::Anon_57)
                .withEvent(PEvents.eSpec_SocketClosed.class, this::Anon_58)
                .withEvent(PEvents.eSpec_ConnectionReset.class, this::Anon_59)
                .withEvent(PEvents.eSpec_IOExceptionRaised.class, this::Anon_60)
                .build());
        }
        
        public java.util.List<Class<? extends pobserve.runtime.events.PEvent<?>>> getEventTypes() {
            return java.util.Arrays.asList(PEvents.eSpec_ConnectionEstablished.class, PEvents.eSpec_ConnectionReset.class, PEvents.eSpec_EOFRead.class, PEvents.eSpec_IOExceptionRaised.class, PEvents.eSpec_ShutdownOutputCompleted.class, PEvents.eSpec_SocketClosed.class);
        }
        
        private void Anon_49(PTypes.PTuple_clnts_srvrs_accpt payload_49) {
            long TMP_tmp0_56;
            long TMP_tmp1_43;
            long TMP_tmp2_42;
            long TMP_tmp3_39;
            long TMP_tmp4_33;
            long TMP_tmp5_32;
            
            TMP_tmp0_56 = payload_49.clientSocket;
            TMP_tmp1_43 = payload_49.acceptedSocket;
            TMP_tmp2_42 = TMP_tmp1_43;
            peerOf_4.put(TMP_tmp0_56,TMP_tmp2_42);
            TMP_tmp3_39 = payload_49.acceptedSocket;
            TMP_tmp4_33 = payload_49.clientSocket;
            TMP_tmp5_32 = TMP_tmp4_33;
            peerOf_4.put(TMP_tmp3_39,TMP_tmp5_32);
        }
        private void Anon_50(PTypes.PTuple_sckt payload_50) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_57;
            boolean TMP_tmp1_44;
            long TMP_tmp2_43;
            boolean TMP_tmp3_40;
            boolean TMP_tmp4_34;
            boolean TMP_tmp5_33;
            long TMP_tmp6_24;
            
            TMP_tmp0_57 = payload_50.socket;
            TMP_tmp1_44 = peerOf_4.containsKey(TMP_tmp0_57);
            TMP_tmp5_33 = TMP_tmp1_44;
            if (TMP_tmp5_33) {
                TMP_tmp2_43 = payload_50.socket;
                TMP_tmp3_40 = excused_2.contains(TMP_tmp2_43);
                TMP_tmp4_34 = !(TMP_tmp3_40);
                TMP_tmp5_33 = TMP_tmp4_34;
            }
            if (TMP_tmp5_33) {
                TMP_tmp6_24 = payload_50.socket;
                pendingEOF.add(TMP_tmp6_24);
                gotoState(PrtStates.PendingEOFDelivery);
                return;
            }
        }
        private void Anon_51(PTypes.PTuple_sckt payload_51) {
            long TMP_tmp0_58;
            boolean TMP_tmp1_45;
            long TMP_tmp2_44;
            long TMP_tmp3_41;
            
            TMP_tmp0_58 = payload_51.socket;
            TMP_tmp1_45 = peerOf_4.containsKey(TMP_tmp0_58);
            if (TMP_tmp1_45) {
                TMP_tmp2_44 = payload_51.socket;
                TMP_tmp3_41 = peerOf_4.get(TMP_tmp2_44);
                pendingEOF.remove(TMP_tmp3_41);
            }
        }
        private void Anon_52(PTypes.PTuple_sckt payload_52) {
            long TMP_tmp0_59;
            
            TMP_tmp0_59 = payload_52.socket;
            ExcusePeerObligation_2(TMP_tmp0_59);
        }
        private void Anon_53(PTypes.PTuple_sckt payload_53) {
            long TMP_tmp0_60;
            
            TMP_tmp0_60 = payload_53.socket;
            ExcuseBothSides_2(TMP_tmp0_60);
        }
        private void Anon_54(PTypes.PTuple_sckt_oprtn payload_54) {
            String TMP_tmp0_61;
            String TMP_tmp1_46;
            boolean TMP_tmp2_45;
            long TMP_tmp3_42;
            
            TMP_tmp0_61 = payload_54.operation;
            TMP_tmp1_46 = "close";
            TMP_tmp2_45 = (pobserve.runtime.values.Equality.deepEquals(TMP_tmp0_61, TMP_tmp1_46) == true);
            if (TMP_tmp2_45) {
                TMP_tmp3_42 = payload_54.socket;
                ExcusePeerObligation_2(TMP_tmp3_42);
            }
        }
        private void Anon_55(PTypes.PTuple_clnts_srvrs_accpt payload_55) {
            long TMP_tmp0_62;
            long TMP_tmp1_47;
            long TMP_tmp2_46;
            long TMP_tmp3_43;
            long TMP_tmp4_35;
            long TMP_tmp5_34;
            
            TMP_tmp0_62 = payload_55.clientSocket;
            TMP_tmp1_47 = payload_55.acceptedSocket;
            TMP_tmp2_46 = TMP_tmp1_47;
            peerOf_4.put(TMP_tmp0_62,TMP_tmp2_46);
            TMP_tmp3_43 = payload_55.acceptedSocket;
            TMP_tmp4_35 = payload_55.clientSocket;
            TMP_tmp5_34 = TMP_tmp4_35;
            peerOf_4.put(TMP_tmp3_43,TMP_tmp5_34);
        }
        private void Anon_56(PTypes.PTuple_sckt payload_56) {
            long TMP_tmp0_63;
            boolean TMP_tmp1_48;
            long TMP_tmp2_47;
            boolean TMP_tmp3_44;
            boolean TMP_tmp4_36;
            boolean TMP_tmp5_35;
            long TMP_tmp6_25;
            
            TMP_tmp0_63 = payload_56.socket;
            TMP_tmp1_48 = peerOf_4.containsKey(TMP_tmp0_63);
            TMP_tmp5_35 = TMP_tmp1_48;
            if (TMP_tmp5_35) {
                TMP_tmp2_47 = payload_56.socket;
                TMP_tmp3_44 = excused_2.contains(TMP_tmp2_47);
                TMP_tmp4_36 = !(TMP_tmp3_44);
                TMP_tmp5_35 = TMP_tmp4_36;
            }
            if (TMP_tmp5_35) {
                TMP_tmp6_25 = payload_56.socket;
                pendingEOF.add(TMP_tmp6_25);
            }
        }
        private void Anon_57(PTypes.PTuple_sckt payload_57) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_64;
            boolean TMP_tmp1_49;
            long TMP_tmp2_48;
            long TMP_tmp3_45;
            boolean TMP_tmp4_37;
            boolean TMP_tmp5_36;
            
            TMP_tmp0_64 = payload_57.socket;
            TMP_tmp1_49 = peerOf_4.containsKey(TMP_tmp0_64);
            if (TMP_tmp1_49) {
                TMP_tmp2_48 = payload_57.socket;
                TMP_tmp3_45 = peerOf_4.get(TMP_tmp2_48);
                pendingEOF.remove(TMP_tmp3_45);
            }
            TMP_tmp4_37 = HasPendingEOF();
            TMP_tmp5_36 = !(TMP_tmp4_37);
            if (TMP_tmp5_36) {
                gotoState(PrtStates.NoPendingEOF);
                return;
            }
        }
        private void Anon_58(PTypes.PTuple_sckt payload_58) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_65;
            boolean TMP_tmp1_50;
            boolean TMP_tmp2_49;
            
            TMP_tmp0_65 = payload_58.socket;
            ExcusePeerObligation_2(TMP_tmp0_65);
            TMP_tmp1_50 = HasPendingEOF();
            TMP_tmp2_49 = !(TMP_tmp1_50);
            if (TMP_tmp2_49) {
                gotoState(PrtStates.NoPendingEOF);
                return;
            }
        }
        private void Anon_59(PTypes.PTuple_sckt payload_59) throws pobserve.runtime.exceptions.TransitionException {
            long TMP_tmp0_66;
            boolean TMP_tmp1_51;
            boolean TMP_tmp2_50;
            
            TMP_tmp0_66 = payload_59.socket;
            ExcuseBothSides_2(TMP_tmp0_66);
            TMP_tmp1_51 = HasPendingEOF();
            TMP_tmp2_50 = !(TMP_tmp1_51);
            if (TMP_tmp2_50) {
                gotoState(PrtStates.NoPendingEOF);
                return;
            }
        }
        private void Anon_60(PTypes.PTuple_sckt_oprtn payload_60) throws pobserve.runtime.exceptions.TransitionException {
            String TMP_tmp0_67;
            String TMP_tmp1_52;
            boolean TMP_tmp2_51;
            long TMP_tmp3_46;
            boolean TMP_tmp4_38;
            boolean TMP_tmp5_37;
            
            TMP_tmp0_67 = payload_60.operation;
            TMP_tmp1_52 = "close";
            TMP_tmp2_51 = (pobserve.runtime.values.Equality.deepEquals(TMP_tmp0_67, TMP_tmp1_52) == true);
            if (TMP_tmp2_51) {
                TMP_tmp3_46 = payload_60.socket;
                ExcusePeerObligation_2(TMP_tmp3_46);
                TMP_tmp4_38 = HasPendingEOF();
                TMP_tmp5_37 = !(TMP_tmp4_38);
                if (TMP_tmp5_37) {
                    gotoState(PrtStates.NoPendingEOF);
                    return;
                }
            }
        }
        private void ExcusePeerObligation_2(long socket_5) {
            long writer_4 = 0L;
            boolean TMP_tmp0_68;
            long TMP_tmp1_53;
            long TMP_tmp2_52;
            long TMP_tmp3_47;
            
            TMP_tmp0_68 = peerOf_4.containsKey(socket_5);
            if (TMP_tmp0_68) {
                TMP_tmp1_53 = peerOf_4.get(socket_5);
                TMP_tmp2_52 = TMP_tmp1_53;
                writer_4 = TMP_tmp2_52;
                TMP_tmp3_47 = writer_4;
                excused_2.add(TMP_tmp3_47);
                pendingEOF.remove(writer_4);
            }
        }
        private void ExcuseBothSides_2(long socket_6) {
            long TMP_tmp0_69;
            boolean TMP_tmp1_54;
            long TMP_tmp2_53;
            long TMP_tmp3_48;
            
            TMP_tmp0_69 = socket_6;
            excused_2.add(TMP_tmp0_69);
            pendingEOF.remove(socket_6);
            TMP_tmp1_54 = peerOf_4.containsKey(socket_6);
            if (TMP_tmp1_54) {
                TMP_tmp2_53 = peerOf_4.get(socket_6);
                excused_2.add(TMP_tmp2_53);
                TMP_tmp3_48 = peerOf_4.get(socket_6);
                pendingEOF.remove(TMP_tmp3_48);
            }
        }
        private boolean HasPendingEOF() {
            long sock_2 = 0L;
            LinkedHashSet<Long> TMP_tmp0_70;
            long TMP_i_sock_tmp1_2 = 0L;
            long sizeof_sock_tmp2_2 = 0L;
            long TMP_tmp3_49;
            long TMP_tmp4_39;
            boolean TMP_tmp5_38;
            boolean TMP_tmp6_26;
            long TMP_tmp7_23;
            long TMP_tmp8_15;
            long TMP_tmp9_14;
            boolean TMP_tmp10_9;
            boolean TMP_tmp11_9;
            
            TMP_tmp0_70 = pobserve.runtime.values.Clone.deepClone(pendingEOF);
            TMP_i_sock_tmp1_2 = -1L;
            TMP_tmp3_49 = TMP_tmp0_70.size();
            sizeof_sock_tmp2_2 = TMP_tmp3_49;
            while ((true)) {
                TMP_tmp4_39 = sizeof_sock_tmp2_2 - 1L;
                TMP_tmp5_38 = TMP_i_sock_tmp1_2 < TMP_tmp4_39;
                TMP_tmp6_26 = TMP_tmp5_38;
                if (TMP_tmp6_26) {
                }
                else
                {
                    break;
                }
                TMP_tmp7_23 = TMP_i_sock_tmp1_2 + 1L;
                TMP_i_sock_tmp1_2 = TMP_tmp7_23;
                TMP_tmp8_15 = pobserve.runtime.values.SetIndexing.elementAt(TMP_tmp0_70, TMP_i_sock_tmp1_2);
                TMP_tmp9_14 = TMP_tmp8_15;
                sock_2 = TMP_tmp9_14;
                TMP_tmp10_9 = excused_2.contains(sock_2);
                TMP_tmp11_9 = !(TMP_tmp10_9);
                if (TMP_tmp11_9) {
                    return (true);
                }
            }
            return (false);
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder("EOFLiveness");
            sb.append("[");
            sb.append("peerOf=" + peerOf_4);
            sb.append(", pendingEOF=" + pendingEOF);
            sb.append(", excused=" + excused_2);
            sb.append("]");
            return sb.toString();
        } // toString()
        
        public boolean deepEquals(EOFLiveness other) {
            return (true
                && pobserve.runtime.values.Equality.deepEquals(this.peerOf_4, other.peerOf_4)
                && pobserve.runtime.values.Equality.deepEquals(this.pendingEOF, other.pendingEOF)
                && pobserve.runtime.values.Equality.deepEquals(this.excused_2, other.excused_2)
            );
        } // deepEquals()
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass()) && this.deepEquals((EOFLiveness)other);
        } // equals()
        
        public int hashCode() {
            return Objects.hash(peerOf_4, pendingEOF, excused_2);
        } // hashCode()
        
    } // EOFLiveness monitor definition
}
