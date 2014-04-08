package myPackage;

public class SessionState 
{
	public int sessionID;
	public int version;
	public String message;
	public long timeout;
	
	public SessionState(int s, int v, String m, long t)
	{
		sessionID = s;
		version = v;
		message = m;
		timeout = t;
	}
}
