package generatedOutput.pobserve;

/***************************************************************************
 * This file was auto-generated on Saturday, 14 March 2026 at 18:40:29.
 * Please do not edit manually!
 **************************************************************************/

import java.io.Serializable;
import java.util.*;
import java.util.logging.*;

public class PTypes {
    /* Tuples */
    
    public static class PTuple_clnts_srvrs_accpt implements pobserve.runtime.values.PValue<PTuple_clnts_srvrs_accpt>, Serializable {
        // (clientSocket:machine,serverSocket:machine,acceptedSocket:machine)
        public long clientSocket;
        public long serverSocket;
        public long acceptedSocket;
        
        public PTuple_clnts_srvrs_accpt() {
            this.clientSocket = 0L;
            this.serverSocket = 0L;
            this.acceptedSocket = 0L;
        }
        
        public PTuple_clnts_srvrs_accpt(long clientSocket, long serverSocket, long acceptedSocket) {
            this.clientSocket = clientSocket;
            this.serverSocket = serverSocket;
            this.acceptedSocket = acceptedSocket;
        }
        
        public PTuple_clnts_srvrs_accpt deepClone() {
            return new PTuple_clnts_srvrs_accpt(clientSocket, serverSocket, acceptedSocket);
        } // deepClone()
        
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass() && 
                this.deepEquals((PTuple_clnts_srvrs_accpt)other)
            );
        } // equals()
        
        public int hashCode() {
            return Objects.hash(clientSocket, serverSocket, acceptedSocket);
        } // hashCode()
        
        public boolean deepEquals(PTuple_clnts_srvrs_accpt other) {
            return (true
                 && this.clientSocket == other.clientSocket
                 && this.serverSocket == other.serverSocket
                 && this.acceptedSocket == other.acceptedSocket
            );
        } // deepEquals()
        
        
        public String toString() {
            StringBuilder sb = new StringBuilder("PTuple_clnts_srvrs_accpt");
            sb.append("[");
            sb.append("clientSocket=" + clientSocket);
            sb.append(", serverSocket=" + serverSocket);
            sb.append(", acceptedSocket=" + acceptedSocket);
            sb.append("]");
            return sb.toString();
        } // toString()
        
    } //PTuple_clnts_srvrs_accpt class definition
    
    public static class PTuple_sckt_dat implements pobserve.runtime.values.PValue<PTuple_sckt_dat>, Serializable {
        // (socket:machine,dat:seq[int])
        public long socket;
        public ArrayList<Long> dat;
        
        public PTuple_sckt_dat() {
            this.socket = 0L;
            this.dat = new ArrayList<Long>();
        }
        
        public PTuple_sckt_dat(long socket, ArrayList<Long> dat) {
            this.socket = socket;
            this.dat = dat;
        }
        
        public PTuple_sckt_dat deepClone() {
            return new PTuple_sckt_dat(socket, pobserve.runtime.values.Clone.deepClone(dat));
        } // deepClone()
        
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass() && 
                this.deepEquals((PTuple_sckt_dat)other)
            );
        } // equals()
        
        public int hashCode() {
            return Objects.hash(socket, dat);
        } // hashCode()
        
        public boolean deepEquals(PTuple_sckt_dat other) {
            return (true
                 && this.socket == other.socket
                 && pobserve.runtime.values.Equality.deepEquals(this.dat, other.dat)
            );
        } // deepEquals()
        
        
        public String toString() {
            StringBuilder sb = new StringBuilder("PTuple_sckt_dat");
            sb.append("[");
            sb.append("socket=" + socket);
            sb.append(", dat=" + dat);
            sb.append("]");
            return sb.toString();
        } // toString()
        
    } //PTuple_sckt_dat class definition
    
    public static class PTuple_sckt implements pobserve.runtime.values.PValue<PTuple_sckt>, Serializable {
        // (socket:machine)
        public long socket;
        
        public PTuple_sckt() {
            this.socket = 0L;
        }
        
        public PTuple_sckt(long socket) {
            this.socket = socket;
        }
        
        public PTuple_sckt deepClone() {
            return new PTuple_sckt(socket);
        } // deepClone()
        
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass() && 
                this.deepEquals((PTuple_sckt)other)
            );
        } // equals()
        
        public int hashCode() {
            return Objects.hash(socket);
        } // hashCode()
        
        public boolean deepEquals(PTuple_sckt other) {
            return (true
                 && this.socket == other.socket
            );
        } // deepEquals()
        
        
        public String toString() {
            StringBuilder sb = new StringBuilder("PTuple_sckt");
            sb.append("[");
            sb.append("socket=" + socket);
            sb.append("]");
            return sb.toString();
        } // toString()
        
    } //PTuple_sckt class definition
    
    public static class PTuple_sckt_oprtn implements pobserve.runtime.values.PValue<PTuple_sckt_oprtn>, Serializable {
        // (socket:machine,operation:string)
        public long socket;
        public String operation;
        
        public PTuple_sckt_oprtn() {
            this.socket = 0L;
            this.operation = "";
        }
        
        public PTuple_sckt_oprtn(long socket, String operation) {
            this.socket = socket;
            this.operation = operation;
        }
        
        public PTuple_sckt_oprtn deepClone() {
            return new PTuple_sckt_oprtn(socket, operation);
        } // deepClone()
        
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass() && 
                this.deepEquals((PTuple_sckt_oprtn)other)
            );
        } // equals()
        
        public int hashCode() {
            return Objects.hash(socket, operation);
        } // hashCode()
        
        public boolean deepEquals(PTuple_sckt_oprtn other) {
            return (true
                 && this.socket == other.socket
                 && pobserve.runtime.values.Equality.deepEquals(this.operation, other.operation)
            );
        } // deepEquals()
        
        
        public String toString() {
            StringBuilder sb = new StringBuilder("PTuple_sckt_oprtn");
            sb.append("[");
            sb.append("socket=" + socket);
            sb.append(", operation=" + operation);
            sb.append("]");
            return sb.toString();
        } // toString()
        
    } //PTuple_sckt_oprtn class definition
    
    public static class PTuple_sckt_rprtd implements pobserve.runtime.values.PValue<PTuple_sckt_rprtd>, Serializable {
        // (socket:machine,reportedCount:int)
        public long socket;
        public long reportedCount;
        
        public PTuple_sckt_rprtd() {
            this.socket = 0L;
            this.reportedCount = 0L;
        }
        
        public PTuple_sckt_rprtd(long socket, long reportedCount) {
            this.socket = socket;
            this.reportedCount = reportedCount;
        }
        
        public PTuple_sckt_rprtd deepClone() {
            return new PTuple_sckt_rprtd(socket, reportedCount);
        } // deepClone()
        
        
        public boolean equals(Object other) {
            return (this.getClass() == other.getClass() && 
                this.deepEquals((PTuple_sckt_rprtd)other)
            );
        } // equals()
        
        public int hashCode() {
            return Objects.hash(socket, reportedCount);
        } // hashCode()
        
        public boolean deepEquals(PTuple_sckt_rprtd other) {
            return (true
                 && this.socket == other.socket
                 && this.reportedCount == other.reportedCount
            );
        } // deepEquals()
        
        
        public String toString() {
            StringBuilder sb = new StringBuilder("PTuple_sckt_rprtd");
            sb.append("[");
            sb.append("socket=" + socket);
            sb.append(", reportedCount=" + reportedCount);
            sb.append("]");
            return sb.toString();
        } // toString()
        
    } //PTuple_sckt_rprtd class definition
    
    
}
