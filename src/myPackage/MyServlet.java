package myPackage;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * Servlet implementation class MyServlet
 */
@WebServlet("/MyServlet")
public class MyServlet extends HttpServlet 
{
	private static final long serialVersionUID = 1L;
	private static int sessionID = 1;
	private String cookieName = "CS5300PROJ1SESSION";
	private final int expiry = 10;
	private final String location = "0";
	private static AtomicInteger callId = new AtomicInteger();
	
	private Thread daemonThread;
	private boolean threadStarted = false;
    
	// hash map used to store session information
	// K: sessionID, V: SessionState
	private ConcurrentHashMap<Integer, SessionState> map = new ConcurrentHashMap<Integer, SessionState>();
	
    /**
     * @see HttpServlet#HttpServlet()
     */
    public MyServlet() {
        super();
    }

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
	{
		// start the daemon thread that removes expired sessions from our map
		if (!threadStarted)
		{
			daemonThread = new Thread(new Runnable()
			{
				@Override
				public void run()
				{
					while(true)
					{
						List<Integer> list = new ArrayList<Integer>(map.keySet());
						for (int id : list)
						{
							if (map.get(id).timeout < (int)System.currentTimeMillis() / 1000)
							{
								map.remove(id);
							}
						}
						try {
							Thread.sleep(10*1000);		// 10 seconds
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();		// restore interrupted status
						}
					}
				}
			});
			daemonThread.setDaemon(true);	// making this thread daemon
		    daemonThread.start();
			threadStarted = true;
		}
		
		Cookie[] cookies = request.getCookies();
		Cookie myCookie = findCookie(cookies, cookieName);		// current client's cookie
		
		String[] sess = new String[2];
		int ver;
		long timeout;
		String[] loc = new String[2];
		String message = "";
		String value = "";
		Cookie c;
		InetAddress local_ip = getIP();
		
		// check if this is client's first request. if so, construct new cookie and new SessionState
		if (myCookie == null)
		{
			sess[0] = String.valueOf(sessionID);
			sess[1] = local_ip.getHostAddress();
			ver = 1;
			message = "Hello, User!";
			long curTime = System.currentTimeMillis() / 1000;
			timeout = curTime + expiry;
			loc[0] = getIP().getHostAddress();
			loc[1] = location;				// TODO: choose random server from local server's View
			value = concatValue(sess, ver, loc);
			
			// store new info to map
			SessionState state = new SessionState(sess, ver, message, timeout, loc);
			map.put(Integer.valueOf(sess[0]), state);
			
			// construct cookie
			c = new Cookie(cookieName, value);
			c.setMaxAge(expiry);	// set timeout!!!
			c.setComment(message);
			
			// send cookie back to client
			response.addCookie(c);
			
			// forward information to jsp page
			request.setAttribute("myVal", c.getValue());
			request.setAttribute("myMessage", c.getComment());
	        request.getRequestDispatcher("/myServlet.jsp").forward(request, response);

			sessionID++;
		}
		// otherwise, check if SessionState is stored in local server
		// i.e. check server_primary or server_backup == server_local
		else 
		{
			String[] locs = getLoc(myCookie);
			boolean flag = false;
			for (String s : locs)
			{
				// if the SessionState is stored in local server, simply reconstruct cookie and update it
				if (!flag && (local_ip.getHostAddress()).equals(s))
				{
					sess[0] = String.valueOf(getID(myCookie));
					sess[1] = getIP(myCookie);
					SessionState ss = map.get(Integer.valueOf(sess[0]));
					if (ss == null)
						throw new ServletException("Current session has timed out.");
					ver = ss.version;
					ver++;
					message = ss.message;
					long curTime = System.currentTimeMillis() / 1000;
					timeout = curTime + expiry;
					loc[0] = getIP().getHostAddress();					
					// choose a backup server
					// TODO: call SessionWrite() to backup server and wait for successful response
					// if (fail) { loc[1] = null; }
					// else { loc[1] = // TODO: backup server - choose at random from local server's view }
					loc[1] = getLoc(myCookie)[1];
					value = concatValue(sess, ver, loc);
					
					// store updated info to map (choose primary server)
					SessionState state = new SessionState(sess, ver, message, timeout, loc);
					map.replace(Integer.valueOf(sess[0]), state);
					
					// kill current cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					// reconstruct cookie
					c = new Cookie(cookieName, value);
					c.setMaxAge(expiry);		// set timeout!!!
					c.setComment(message);
					
					// send cookie back to client
					response.addCookie(c);
					
					// forward information to jsp page
					request.setAttribute("myVal", c.getValue());
					request.setAttribute("myMessage", c.getComment());
			        request.getRequestDispatcher("/myServlet.jsp").forward(request, response);

					flag = true;
				}
			}
			// if the SessionState is stored in another server, execute RPC calls
			if (!flag)
			{
				// TODO: SessionRead stuff here
			}
		}
		
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
	{
		Cookie[] cookies = request.getCookies();
		Cookie myCookie = findCookie(cookies, cookieName);		// current client's cookie
		
		String action = request.getParameter("action");
		String message = null;
		
		// update cookie parameters
		String[] sess = new String[2];
		int ver;
		long timeout;
		String[] loc = new String[2];
		String msg = "";
		String value = "";
		Cookie c;
		InetAddress local_ip = getIP();
		
		// check if SessionState is stored in local server
		// i.e. check server_primary or server_backup == server_local
		String[] locs = getLoc(myCookie);
		boolean flag = false;
		for (String s : locs)
		{
			// if the SessionState is stored in local server, simply reconstruct cookie and update it
			if (!flag && (local_ip.getHostAddress()).equals(s))
			{
				// replace and refresh buttons
				if (!action.equals("logout"))
				{
					sess[0] = String.valueOf(getID(myCookie));
					sess[1] = getIP(myCookie);
					SessionState ss = map.get(Integer.valueOf(sess[0]));
					if (ss == null)
						throw new ServletException("Current session has timed out.");
					ver = ss.version;
					ver++;
					if (action.equals("replace"))
					{
						// retrieve message from form
						message = request.getParameter("message");
					}
					else if (action.equals("refresh"))
					{
						// retrieve message from cookie
						message = ss.message;
					}
					msg = message;
					long curTime = System.currentTimeMillis() / 1000;
					timeout = curTime + expiry;
					loc[0] = getIP().getHostAddress();
					// choose a backup server
					// TODO: call SessionWrite() to backup server and wait for successful response
					// if (fail) { loc[1] = null; }
					// else { loc[1] = // TODO: backup server - choose at random from local server's view }
					loc[1] = ss.location[1];
					value = concatValue(sess, ver, loc);
					
					// store updated info to map (choose primary server)
					SessionState state = new SessionState(sess, ver, msg, timeout, loc);
					map.replace(Integer.valueOf(sess[0]), state);
					
					// kill current cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					// reconstruct cookie
					c = new Cookie(cookieName, value);
					c.setMaxAge(expiry);		// set timeout!!!
					c.setComment(msg);
					
					// send cookie back to client
					response.addCookie(c);
					
					// forward information to jsp page
					request.setAttribute("myVal", c.getValue());
					request.setAttribute("myMessage", c.getComment());
			        request.getRequestDispatcher("/myServlet.jsp").forward(request, response);
				}
				// logout button
				else
				{
					// remove session info from map
					map.remove(getID(myCookie));
					
					// kill the cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					
					// send cookie back to client
					response.addCookie(myCookie);
					
					// forward information to jsp page and display it
			        request.getRequestDispatcher("/logout.jsp").forward(request, response);
				}
				
				flag = true;
			}
		}
		// if the SessionState is stored in another server, execute RPC calls
		if (!flag)
		{
			// TODO: SessionRead stuff here
		}
		
	}

	// search for cookie if it exists already, else return null
	private Cookie findCookie(Cookie[] cookies, String name)
	{
		Cookie myCookie = null;
		
		if (cookies != null)
		{
			for (Cookie c : cookies)
			{
				if (name.equals(c.getName()))
					myCookie = c;
			}
		}
		
		return myCookie;
	}
	
	// returns the session ID of the Cookie c
	private int getID(Cookie c)
	{
		if (c != null)
		{
			String val = c.getValue();
			String[] tokens = val.split("_");
			
			return Integer.valueOf(tokens[0]);
		}
		else 
			return -1;
	}
	// returns the serverID of the Cookie c
	private String getIP(Cookie c)
	{
		if (c != null)
		{
			String val = c.getValue();
			String[] tokens = val.split("_");
			
			return String.valueOf(tokens[1]);
		}
		else 
			return null;
	}
	// returns the locations (primary, backup ips)
	private String[] getLoc(Cookie c)
	{
		String[] ret = new String[2];
		if (c != null)
		{
			String val = c.getValue();
			String[] tokens = val.split("_");
			
			ret[0] = tokens[3];
			ret[1] = tokens[4];
			return ret;
		}
		else 
			return null;
	}
	
	// constructs the value string for session states
	private String concatValue(String[] sess, int ver, String[] loc)
	{
		return sess[0] + "_" + sess[1] + "_" + 
				String.valueOf(ver) + "_" + 
				loc[0] + "_" + loc[1];
	}
	
	// running on local tomcat
	private InetAddress getIP()
	{
		InetAddress addr = null;
		
		try 
		{
			addr = InetAddress.getByName(InetAddress.getLocalHost().getHostAddress());
		} 
		catch (UnknownHostException e) 
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return addr;
	}
	
	// sessionRead, sent by this server to another
	// returns a packet containing the message
	private DatagramPacket sessionRead(
			int sessNum, int serverId , int sessionVersionNum, 
			InetAddress address, int port) {
		//TODO
		try {
			DatagramSocket rpcSocket = new DatagramSocket();
			int newCallId = callId.getAndAdd(1);
			ByteBuffer bbuf = ByteBuffer.allocate(4); //TODO: HOW MANY BYTES??
			byte[] outBuf = bbuf.array();
            DatagramPacket recvPkt = null;
            return recvPkt;
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        return null;
	}
	
	// sessionWrite, sent by this server to another server
	// returns a packet (indicating success)
	private DatagramPacket sessionWrite(
			int sessNum, int serverId, int sessionVersionNum, 
			String msg, int discard_time, InetAddress address, int port) {
		return null;
	}
	
	// for the daemon thread:
	// returns whether the byte array is a read or write request
	private boolean readOrWrite(byte[] buffer) {
		return true;
	}
	
	// unpackage the received byte array (for the rpc handler thread)
	// byte array contains:
	// callId, operationCode, sessionNum, serverId, sessionVersionNum
	// returned int array contains:
	// sessionNum, serverId, sessionVersionNum
	private int[] unpackReadRequest(byte[] buffer) {
		return null;
	}
	
	// unpackage the write request byte array (for the rpc handler thread)
	// byte array contains:
	// callId, operationCode, sessionNum, serverId, sessionVersionNum, msg, discard_time
	// returns a SessionState to be saved onto this server
	private SessionState unpackWriteRequest(byte [] buffer) {
		return null;
	}
	
}
