// %Z%%M%, %I%, %G%
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package javax.jmdns;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * DNS record
 *
 * @author	Arthur van Hoff, Rick Blair, Werner Randelshofer
 * @version 	%I%, %G%
 */
abstract class DNSRecord extends DNSEntry {
    int ttl;
    private long created;
    
    /**
     * Create a DNSRecord with a name, type, clazz, and ttl.
     */
    DNSRecord(String name, int type, int clazz, int ttl) {
        super(name, type, clazz);
        this.ttl = ttl;
        this.created = System.currentTimeMillis();
    }
    
    /**
     * True if this record is the same as some other record.
     */
    public boolean equals(Object other) {
        return (other instanceof DNSRecord) && sameAs((DNSRecord)other);
    }
    
    /**
     * True if this record is the same as some other record.
     */
    boolean sameAs(DNSRecord other) {
        return super.equals(other) && sameValue((DNSRecord)other);
    }
    
    /**
     * True if this record has the same value as some other record.
     */
    abstract boolean sameValue(DNSRecord other);
    
    /**
     * Handles a query represented by this record.
     *
     * @return Returns true if a conflict with one of the services registered
     * with JmDNS or with the hostname occured.
     */
    abstract boolean handleQuery(JmDNS dns, long expirationTime);
    /**
     * Handles a responserepresented by this record.
     *
     * @return Returns true if a conflict with one of the services registered
     * with JmDNS or with the hostname occured.
     */
    abstract boolean handleResponse(JmDNS dns);
    
    /**
     * Adds this as an answer to the provided outgoing datagram.
     */
    abstract DNSOutgoing addAnswer(JmDNS dns, DNSIncoming in, InetAddress addr, int port, DNSOutgoing out) throws IOException;
    
    /**
     * True if this record is suppressed by the answers in a message.
     */
    boolean suppressedBy(DNSIncoming msg) {
        try {
        for (int i = msg.numAnswers ; i-- > 0 ;) {
            if (suppressedBy((DNSRecord)msg.answers.elementAt(i))) {
                return true;
            }
        }
        return false;
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.flush();
            e.printStackTrace();
            System.err.flush();
            msg.print(true);
            return false;
        }
    }
    
    /**
     * True if this record would be supressed by an answer.
     * This is the case if this record would not have a
     * significantly longer TTL.
     */
    boolean suppressedBy(DNSRecord other) {
        if (sameAs(other) && (other.ttl > ttl / 2)) {
            return true;
        }
        return false;
    }
    
    /**
     * Get the expiration time of this record.
     */
    long getExpirationTime(int percent) {
        return created + (percent * ttl * 10L);
    }
    
    /**
     * Get the remaining TTL for this record.
     */
    int getRemainingTTL(long now) {
        return (int)Math.max(0, (getExpirationTime(100) - now) / 1000);
    }
    
    /**
     * Check if the record is expired.
     */
    boolean isExpired(long now) {
        return getExpirationTime(100) <= now;
    }
    
    /**
     * Check if the record is stale, ie it has outlived
     * more than half of its TTL.
     */
    boolean isStale(long now) {
        return getExpirationTime(50) <= now;
    }
    
    /**
     * Reset the TTL of a record. This avoids having to
     * update the entire record in the cache.
     */
    void resetTTL(DNSRecord other) {
        created = other.created;
        ttl = other.ttl;
    }
    
    /**
     * Write this record into an outgoing message.
     */
    abstract void write(DNSOutgoing out) throws IOException;
    
    /**
     * Address record.
     */
    static class Address extends DNSRecord {
        int addr;
        
        Address(String name, int type, int clazz, int ttl, int addr) {
            super(name, type, clazz, ttl);
            this.addr = addr;
        }
        void write(DNSOutgoing out) throws IOException {
            out.writeInt(addr);
        }
        boolean same(DNSRecord other) {
            return ((sameName(other)) && ((sameValue(other))));
        }
        
        boolean sameName(DNSRecord other) {
            return name.equalsIgnoreCase(((Address)other).name);
        }
        
        boolean sameValue(DNSRecord other) {
            return (addr == ((Address)other).addr);
        }
        InetAddress getInetAddress() {
            try {
                return InetAddress.getByName(getAddress());
            } catch (UnknownHostException e) {
                // should not happen
                e.printStackTrace();
                return null;
            }
        }
        String getAddress() {
            return ((addr >> 24) & 0xFF) + "." + ((addr >> 16) & 0xFF) + "." + ((addr >> 8) & 0xFF) + "." + (addr & 0xFF);
        }
        
        /**
         * Creates a byte array representation of this record.
         * This is needed for tie-break tests according to
         * draft-cheshire-dnsext-multicastdns-04.txt chapter 9.2.
         */
        private byte[] toByteArray() {
            try {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                DataOutputStream dout = new DataOutputStream(bout);
                dout.write(name.getBytes("UTF8"));
                dout.writeShort(type);
                dout.writeShort(clazz);
                //dout.writeInt(len);
                dout.writeInt(addr);
                dout.close();
                return bout.toByteArray();
            } catch (IOException e) {
                throw new InternalError();
            }
        }
        
        /**
         * Does a lexicographic comparison of the byte array representation
         * of this record and that record.
         * This is needed for tie-break tests according to
         * draft-cheshire-dnsext-multicastdns-04.txt chapter 9.2.
         */
        private int lexCompare(DNSRecord.Address that) {
            byte[] thisBytes = this.toByteArray();
            byte[] thatBytes = that.toByteArray();
            for(int i=0,n=Math.min(thisBytes.length,thatBytes.length);i<n;i++) {
                if (thisBytes[i] > thatBytes[i]) {
                    return 1;
                } else if (thisBytes[i] < thatBytes[i]) {
                    return -1;
                }
            }
            return thisBytes.length - thatBytes.length;
        }
        
        /**
         * Does the necessary actions, when this as a query.
         */
        boolean handleQuery(JmDNS dns, long expirationTime) {
            if (dns.host.sameName(this) && ! dns.host.sameValue(this)) {
                if (dns.debug > 1) System.out.println("JmDNS DNSRecord.Address.handleQuery() Conflicting probe detected");
                // Tie-breaker test
                if (dns.getState().isProbing() && lexCompare(dns.host) >= 0) {
                    // We lost the tie-break. We have to choose a different name.
                    dns.host.name = dns.incrementHostName(dns.host.getName());
                    dns.getCache().clear();
                    for (Iterator i=dns.services.values().iterator(); i.hasNext(); ) {
                        ServiceInfo info = (ServiceInfo) i.next();
                        info.revertState();
                    }
                    
                }
                dns.revertState();
                return true;
                
            }
            return false;
        }
        /**
         * Does the necessary actions, when this as a response.
         */
        boolean handleResponse(JmDNS dns) {
            if (dns.host.sameName(this) && ! dns.host.sameValue(this)) {
                if (dns.debug > 1) System.out.println("JmDNS DNSRecord.Address.handleResponse() Denial detected");
                if (dns.getState().isProbing()) {
                    dns.host.name = dns.incrementHostName(dns.host.getName());
                    dns.getCache().clear();
                    for (Iterator i=dns.services.values().iterator(); i.hasNext(); ) {
                        ServiceInfo info = (ServiceInfo) i.next();
                        info.revertState();
                    }
                }
                dns.revertState();
                
                return true;
            }
            return false;
        }
        DNSOutgoing addAnswer(JmDNS dns, DNSIncoming in, InetAddress addr, int port, DNSOutgoing out) throws IOException {
            return out;
        }
        public String toString() {
            return toString(getAddress());
        }
    }
    
    /**
     * Pointer record.
     */
    static class Pointer extends DNSRecord {
        String alias;
        
        Pointer(String name, int type, int clazz, int ttl, String alias) {
            super(name, type, clazz, ttl);
            this.alias = alias;
        }
        void write(DNSOutgoing out) throws IOException {
            out.writeName(alias);
        }
        boolean sameValue(DNSRecord other) {
            return alias.equals(((Pointer)other).alias);
        }
        boolean handleQuery(JmDNS dns, long expirationTime) {
            // Nothing to do (?)
            // I think there is no possibility for conflicts for this record type?
            return false;
        }
        boolean handleResponse(JmDNS dns) {
            // Nothing to do (?)
            // I think there is no possibility for conflicts for this record type?
            return false;
        }
        DNSOutgoing addAnswer(JmDNS dns, DNSIncoming in, InetAddress addr, int port, DNSOutgoing out) throws IOException {
            return out;
        }
        public String toString() {
            return toString(alias);
        }
    }
    
    static class Text extends DNSRecord {
        byte text[];
        
        Text(String name, int type, int clazz, int ttl, byte text[]) {
            super(name, type, clazz, ttl);
            this.text = text;
        }
        void write(DNSOutgoing out) throws IOException {
            out.writeBytes(text, 0, text.length);
        }
        boolean sameValue(DNSRecord other) {
            Text txt = (Text)other;
            if (txt.text.length != text.length) {
                return false;
            }
            for (int i = text.length ; i-- > 0 ;) {
                if (txt.text[i] != text[i]) {
                    return false;
                }
            }
            return true;
        }
        boolean handleQuery(JmDNS dns, long expirationTime) {
            // Nothing to do (?)
            // I think there is no possibility for conflicts for this record type?
            return false;
        }
        boolean handleResponse(JmDNS dns) {
            // Nothing to do (?)
            // Shouldn't we care if we get a conflict at this level?
            /*
            ServiceInfo info = (ServiceInfo) dns.services.get(name.toLowerCase());
            if (info != null) {
                if (! Arrays.equals(text,info.text)) {
                    info.revertState();
                    return true;
                }
            }*/
            return false;
        }
        DNSOutgoing addAnswer(JmDNS dns, DNSIncoming in, InetAddress addr, int port, DNSOutgoing out) throws IOException {
            return out;
        }
        public String toString() {
            return toString((text.length > 10) ? new String(text, 0, 7) + "..." : new String(text));
        }
    }
    
    /**
     * Service record.
     */
    static class Service extends DNSRecord {
        int priority;
        int weight;
        int port;
        String server;
        
        Service(String name, int type, int clazz, int ttl, int priority, int weight, int port, String server) {
            super(name, type, clazz, ttl);
            this.priority = priority;
            this.weight = weight;
            this.port = port;
            this.server = server;
        }
        void write(DNSOutgoing out) throws IOException {
            out.writeShort(priority);
            out.writeShort(weight);
            out.writeShort(port);
            out.writeName(server);
        }
        private byte[] toByteArray() {
            try {
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                DataOutputStream dout = new DataOutputStream(bout);
                dout.write(name.getBytes("UTF8"));
                dout.writeShort(type);
                dout.writeShort(clazz);
                //dout.writeInt(len);
                dout.writeShort(priority);
                dout.writeShort(weight);
                dout.writeShort(port);
                dout.write(server.getBytes("UTF8"));
                dout.close();
                return bout.toByteArray();
            } catch (IOException e) {
                throw new InternalError();
            }
        }
        private int lexCompare(DNSRecord.Service that) {
            byte[] thisBytes = this.toByteArray();
            byte[] thatBytes = that.toByteArray();
            for(int i=0,n=Math.min(thisBytes.length,thatBytes.length);i<n;i++) {
                if (thisBytes[i] > thatBytes[i]) {
                    return 1;
                } else if (thisBytes[i] < thatBytes[i]) {
                    return -1;
                }
            }
            return thisBytes.length - thatBytes.length;
        }
        boolean sameValue(DNSRecord other) {
            Service s = (Service)other;
            return (priority == s.priority) && (weight == s.weight) && (port == s.port) && server.equals(s.server);
        }
        boolean handleQuery(JmDNS dns, long expirationTime) {
            ServiceInfo info = (ServiceInfo) dns.services.get(name.toLowerCase());
            if (info != null
            && (port != info.port || ! server.equalsIgnoreCase(dns.host.name))) {
                if (dns.debug > 1) System.out.println("JmDNS DNSRecord.Service.handleQuery() Conflicting probe detected");
                
                // Tie breaker test
                if (info.getState().isProbing() && lexCompare(new DNSRecord.Service( info.getQualifiedName(), DNSConstants.TYPE_SRV,
                DNSConstants.CLASS_IN | DNSConstants.CLASS_UNIQUE,
                DNSConstants.DNS_TTL, info.priority,
                info.weight, info.port, dns.host.name )
                ) >= 0) {
                    // We lost the tie break
                    String oldName = info.getQualifiedName().toLowerCase();
                    info.setName(dns.incrementName(info.getName()));
                    dns.services.remove(oldName);
                    dns.services.put(info.getQualifiedName().toLowerCase(), info);
                    if (dns.debug > 1) System.out.println("JmDNS DNSRecord.Service.handleQuery() Lost tie break: new unique name chosen:"+info.getName());
                }
                info.revertState();
                return true;
                
            }
            return false;
        }
        boolean handleResponse(JmDNS dns) {
            ServiceInfo info = (ServiceInfo) dns.services.get(name.toLowerCase());
            if (info != null
            && (port != info.port || ! server.equalsIgnoreCase(dns.host.name))) {
                if (dns.debug > 1) System.out.println("JmDNS DNSRecord.Service.handleQuery() Denial detected");
                if (info.getState().isProbing()) {
                    String oldName = info.getQualifiedName().toLowerCase();
                    info.setName(dns.incrementName(info.getName()));
                    dns.services.remove(oldName);
                    dns.services.put(info.getQualifiedName().toLowerCase(), info);
                    if (dns.debug > 1) System.out.println("JmDNS DNSRecord.Service.handleResponse() New unique name chose:"+info.getName());
                }
                info.revertState();
                return true;
            }
            return false;
        }
        DNSOutgoing addAnswer(JmDNS dns, DNSIncoming in, InetAddress addr, int port, DNSOutgoing out) throws IOException {
            ServiceInfo info = (ServiceInfo) dns.services.get(name.toLowerCase());
            if (info != null) {
                if (this.port == info.port != server.equals(dns.host.name)) {
                    return dns.addAnswer(in, addr, port, out,
                    new DNSRecord.Service( info.getQualifiedName(), DNSConstants.TYPE_SRV,
                    DNSConstants.CLASS_IN | DNSConstants.CLASS_UNIQUE,
                    DNSConstants.DNS_TTL, info.priority,
                    info.weight, info.port, dns.host.name )
                    );
                }
            }
            return out;
        }
        public String toString() {
            return toString(server + ":" + port);
        }
    }
    
    public String toString(String other) {
        return toString("record", ttl + "/" + getRemainingTTL(System.currentTimeMillis())  + "," + other);
    }
}

