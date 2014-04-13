package myPackage;

public class SessionState 
{
	public SessionTuple sessionID;
	public int version;
	public String message;
	public long timeout;
	
	public SessionState(SessionTuple sid, int v, String m, long t)
	{
		sessionID = sid;
		version = v;
		message = m;
		timeout = t;
	}
}
