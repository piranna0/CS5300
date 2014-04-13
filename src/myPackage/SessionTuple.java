package myPackage;

import java.net.InetAddress;

public class SessionTuple {
	public int sessionNum;
	public String serverId;
	
	public SessionTuple(int n, String addr) {
		sessionNum = n;
		serverId = addr;
	}
	
	@Override
	public boolean equals(Object o) {
		if (o==null) {
			return false;
		} else if (o instanceof SessionTuple) {
			SessionTuple s = (SessionTuple) o;
			return (s.sessionNum==sessionNum && s.serverId.equals(serverId));
		}
		return false;
	}
	
//	@Override
//	public int hashCode() {
//		// TODO
//	}

}
