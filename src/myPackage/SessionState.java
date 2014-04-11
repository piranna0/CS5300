package myPackage;

public class SessionState 
{
	public String[] sessionID = new String[2];
	public int version;
	public String message;
	public long timeout;
	public String[] location = new String[2];
	
	public SessionState(String[] s, int v, String m, long t, String[] l)
	{
		sessionID = s;
		version = v;
		message = m;
		timeout = t;
		location = l;
	}
}
