package generatedOutput.pobserve;

/***************************************************************************
 * This file was auto-generated on Saturday, 14 March 2026 at 18:40:29.
 * Please do not edit manually!
 **************************************************************************/

import java.io.Serializable;
import java.util.*;
import java.util.logging.*;

public class PEvents {
    public static class eSpec_ConnectionEstablished extends pobserve.runtime.events.PEvent<PTypes.PTuple_clnts_srvrs_accpt> implements Serializable {
        public eSpec_ConnectionEstablished(PTypes.PTuple_clnts_srvrs_accpt p) { this.payload = p; }
        private PTypes.PTuple_clnts_srvrs_accpt payload; 
        public PTypes.PTuple_clnts_srvrs_accpt getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_ConnectionEstablished[" + payload + "]"; }
    } // eSpec_ConnectionEstablished
    
    public static class eSpec_DataRead extends pobserve.runtime.events.PEvent<PTypes.PTuple_sckt_dat> implements Serializable {
        public eSpec_DataRead(PTypes.PTuple_sckt_dat p) { this.payload = p; }
        private PTypes.PTuple_sckt_dat payload; 
        public PTypes.PTuple_sckt_dat getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_DataRead[" + payload + "]"; }
    } // eSpec_DataRead
    
    public static class eSpec_DataWritten extends pobserve.runtime.events.PEvent<PTypes.PTuple_sckt_dat> implements Serializable {
        public eSpec_DataWritten(PTypes.PTuple_sckt_dat p) { this.payload = p; }
        private PTypes.PTuple_sckt_dat payload; 
        public PTypes.PTuple_sckt_dat getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_DataWritten[" + payload + "]"; }
    } // eSpec_DataWritten
    
    public static class eSpec_SocketClosed extends pobserve.runtime.events.PEvent<PTypes.PTuple_sckt> implements Serializable {
        public eSpec_SocketClosed(PTypes.PTuple_sckt p) { this.payload = p; }
        private PTypes.PTuple_sckt payload; 
        public PTypes.PTuple_sckt getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_SocketClosed[" + payload + "]"; }
    } // eSpec_SocketClosed
    
    public static class eSpec_SocketAccepted extends pobserve.runtime.events.PEvent<PTypes.PTuple_sckt> implements Serializable {
        public eSpec_SocketAccepted(PTypes.PTuple_sckt p) { this.payload = p; }
        private PTypes.PTuple_sckt payload; 
        public PTypes.PTuple_sckt getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_SocketAccepted[" + payload + "]"; }
    } // eSpec_SocketAccepted
    
    public static class eSpec_SocketConnected extends pobserve.runtime.events.PEvent<PTypes.PTuple_sckt> implements Serializable {
        public eSpec_SocketConnected(PTypes.PTuple_sckt p) { this.payload = p; }
        private PTypes.PTuple_sckt payload; 
        public PTypes.PTuple_sckt getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_SocketConnected[" + payload + "]"; }
    } // eSpec_SocketConnected
    
    public static class eSpec_IOExceptionRaised extends pobserve.runtime.events.PEvent<PTypes.PTuple_sckt_oprtn> implements Serializable {
        public eSpec_IOExceptionRaised(PTypes.PTuple_sckt_oprtn p) { this.payload = p; }
        private PTypes.PTuple_sckt_oprtn payload; 
        public PTypes.PTuple_sckt_oprtn getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_IOExceptionRaised[" + payload + "]"; }
    } // eSpec_IOExceptionRaised
    
    public static class eSpec_ShutdownOutputCompleted extends pobserve.runtime.events.PEvent<PTypes.PTuple_sckt> implements Serializable {
        public eSpec_ShutdownOutputCompleted(PTypes.PTuple_sckt p) { this.payload = p; }
        private PTypes.PTuple_sckt payload; 
        public PTypes.PTuple_sckt getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_ShutdownOutputCompleted[" + payload + "]"; }
    } // eSpec_ShutdownOutputCompleted
    
    public static class eSpec_ShutdownInputCompleted extends pobserve.runtime.events.PEvent<PTypes.PTuple_sckt> implements Serializable {
        public eSpec_ShutdownInputCompleted(PTypes.PTuple_sckt p) { this.payload = p; }
        private PTypes.PTuple_sckt payload; 
        public PTypes.PTuple_sckt getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_ShutdownInputCompleted[" + payload + "]"; }
    } // eSpec_ShutdownInputCompleted
    
    public static class eSpec_AvailableQueried extends pobserve.runtime.events.PEvent<PTypes.PTuple_sckt_rprtd> implements Serializable {
        public eSpec_AvailableQueried(PTypes.PTuple_sckt_rprtd p) { this.payload = p; }
        private PTypes.PTuple_sckt_rprtd payload; 
        public PTypes.PTuple_sckt_rprtd getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_AvailableQueried[" + payload + "]"; }
    } // eSpec_AvailableQueried
    
    public static class eSpec_ConnectionReset extends pobserve.runtime.events.PEvent<PTypes.PTuple_sckt> implements Serializable {
        public eSpec_ConnectionReset(PTypes.PTuple_sckt p) { this.payload = p; }
        private PTypes.PTuple_sckt payload; 
        public PTypes.PTuple_sckt getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_ConnectionReset[" + payload + "]"; }
    } // eSpec_ConnectionReset
    
    public static class eSpec_EOFRead extends pobserve.runtime.events.PEvent<PTypes.PTuple_sckt> implements Serializable {
        public eSpec_EOFRead(PTypes.PTuple_sckt p) { this.payload = p; }
        private PTypes.PTuple_sckt payload; 
        public PTypes.PTuple_sckt getPayload() { return payload; }
        
        @Override
        public String toString() { return "eSpec_EOFRead[" + payload + "]"; }
    } // eSpec_EOFRead
    
}
