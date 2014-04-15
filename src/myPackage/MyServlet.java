package myPackage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Random;
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
	private static final int ViewSz = 5;
	private static AtomicInteger sessionID = new AtomicInteger();
	private String cookieName = "CS5300PROJ1SESSION";
	public static int SESSION_TIMEOUT_SECS = 60;
	public static int DISCARD_TIME_DELTA = 1;
	private static AtomicInteger callId = new AtomicInteger();
	private final byte SESSIONREAD = 0; // operation code
	private final byte SESSIONWRITE = 1; // operation code
	private final byte GETVIEW = 2; //operation code
	private final int PORT = 5300;
	private String SvrID;
	private static int TIMEOUT = 1000;

	private static int GOSSIP_MSECS = 20000;
	private static int BOOTSTRAP_MSECS = 40000;

	private View view = new View();

	// Session State table, aka hash map used to store session information
	// K: sessionID, V: SessionState
	private ConcurrentHashMap<SessionTuple, SessionState> map;

	// spawns the garbage collector daemon thread
	public void garbageCollector()
	{
		Thread daemonThread = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				while(true)
				{
					for (SessionTuple tup : map.keySet())
					{
						SessionState state = map.get(tup);
						if (state.timeout < (int)(System.currentTimeMillis()/1000))
						{
							SessionState s = map.remove(tup);
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
	}

	/**
	 * @see HttpServlet#HttpServlet()
	 */
	public MyServlet() {
		super();
		map = new ConcurrentHashMap<SessionTuple, SessionState>();
		setSvrID();

		garbageCollector(); 
		ViewDB.init();
		rpcServer();
		bootstrap();
		gossip();
	}


	public void setSvrID(){
//		try {
//			SvrID = InetAddress.getLocalHost().getHostAddress();
//		} catch (UnknownHostException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}

		Runtime r = Runtime.getRuntime();
		Process blah;
		String IPAddress = "0.0.0.0";
		try {
			blah = r.exec("/opt/aws/bin/ec2-metadata --public-ipv4");

			BufferedReader in = new BufferedReader(new InputStreamReader(blah.getInputStream()));
			String line = null;
			while((line = in.readLine()) != null){
				IPAddress = line;
			}
			SvrID = IPAddress.split(" ")[1];
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			setSvrID();
		}
	}
	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
	{	
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
		if (myCookie == null || map.size() == 0)		// TODO i added this
		{
			int sid = sessionID.getAndAdd(1);
			sess[0] = String.valueOf(sid);
			sess[1] = local_ip.getHostAddress();
			ver = 1;
			message = SvrID;
			long curTime = System.currentTimeMillis() / 1000;
			timeout = curTime + SESSION_TIMEOUT_SECS;

			loc[0] = SvrID;

			// choose a backup server from view and call SessionWrite()
			boolean reply = false;
			View myCopy = View.copy(view);
			while (reply == false)
			{
				String backup_ip = View.choose(myCopy);
				View.remove(myCopy, backup_ip);
				if (backup_ip.equals("0.0.0.0")) 
				{
					// nothing's in view
					loc[1] = "0.0.0.0";
					break;
				}
				else
				{
					reply = sessionWrite(sid, sess[1], ver, message, InetAddress.getByName(backup_ip));
					if (reply == true)
					{
						loc[1] = backup_ip;
						break;
					}
					else{
						RPCtimeout(InetAddress.getByName(backup_ip));
					}
				}
			}
			value = concatValue(sess, ver, loc);

			// store new info to map
			SessionTuple sessTup = new SessionTuple(sid, sess[1]);
			SessionState state = new SessionState(sessTup, ver, message, timeout);
			map.put(sessTup, state);

			// construct cookie
			c = new Cookie(cookieName, concatPrint(sess, ver, loc));
			c.setMaxAge(SESSION_TIMEOUT_SECS);	// set timeout!!!
			c.setComment(message);
			
			// send cookie back to client
			response.addCookie(c);

			// forward information to jsp page
			request.setAttribute("myLocal", SvrID);
			request.setAttribute("myVal", concatPrint(sess, ver, loc));
			request.setAttribute("myMessage", c.getComment());
			request.setAttribute("myLocation", "New session created and stored in " + SvrID);
			request.setAttribute("myExp", (int)(System.currentTimeMillis()/1000) + SESSION_TIMEOUT_SECS);
			request.setAttribute("myDis", (int)(System.currentTimeMillis()/1000) + SESSION_TIMEOUT_SECS + DISCARD_TIME_DELTA);
			request.setAttribute("myView", view.toString());
			request.getRequestDispatcher("/myServlet.jsp").forward(request, response);

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
				if (!flag && (SvrID.equals(s)))
				{
					int sid = getID(myCookie);
					sess[0] = String.valueOf(sid);
					sess[1] = getIP(myCookie);
					SessionState ss = map.get(new SessionTuple(sid, sess[1]));
					if (ss == null)
					{
						// forward information to jsp page and display it
						request.setAttribute("loc", "line 242");
						
						// send a dead cookie
						Cookie myDeadCookie = new Cookie(cookieName, "");
						myDeadCookie.setMaxAge(0);
						myDeadCookie.setValue(null);

						// send cookie back to client
						response.addCookie(myDeadCookie);
						request.getRequestDispatcher("/error.jsp").forward(request, response);
						return;
					}
					ver = ss.version;
					ver++;
					message = ss.message;
					long curTime = System.currentTimeMillis() / 1000;
					timeout = curTime + SESSION_TIMEOUT_SECS;
					loc[0] = SvrID;

					// choose a backup server from view and call SessionWrite()
					boolean reply = false;
					View myCopy = View.copy(view);
					while (reply == false)
					{
						String backup_ip = View.choose(myCopy);
						View.remove(myCopy, backup_ip);
						if (backup_ip.equals("0.0.0.0"))
						{
							// nothing's in view
							loc[1] = "0.0.0.0";
							break;
						}
						else 
						{
							reply = sessionWrite(sid, sess[1], ver, message, InetAddress.getByName(backup_ip));
							if (reply == true)
							{
								loc[1] = backup_ip;
								break;
							}
							else{
								RPCtimeout(InetAddress.getByName(backup_ip));
							}
						}
					}
					value = concatValue(sess, ver, loc);

					// store updated info to map (choose primary server)
					SessionTuple sessTup = new SessionTuple(sid, sess[1]);
					SessionState state = new SessionState(sessTup, ver, message, timeout);
					map.replace(sessTup, state);

					// kill current cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					// reconstruct cookie
					c = new Cookie(cookieName, concatPrint(sess, ver, loc));
					c.setMaxAge(SESSION_TIMEOUT_SECS);		// set timeout!!!
					c.setComment(message);

					// send cookie back to client
					response.addCookie(c);

					// forward information to jsp page
					request.setAttribute("myLocal", SvrID);
					request.setAttribute("myVal", concatPrint(sess, ver, loc));
					request.setAttribute("myMessage", c.getComment());
					request.setAttribute("myLocation", "Existing session found LOCALLY in " + SvrID);
					request.setAttribute("myExp", (int)(System.currentTimeMillis()/1000) + SESSION_TIMEOUT_SECS);
					request.setAttribute("myDis", (int)(System.currentTimeMillis()/1000) + SESSION_TIMEOUT_SECS + DISCARD_TIME_DELTA);
					request.setAttribute("myView", view.toString());
					request.getRequestDispatcher("/myServlet.jsp").forward(request, response);

					flag = true;
				}
			}
			// if the SessionState is stored in another server, execute RPC calls
			if (!flag)
			{
				// extract relevant values for sessionRead
				int sessNum = getID(myCookie);
				String servNum = getIP(myCookie);
				int verNum = getVer(myCookie);
				String[] strLocs = getLoc(myCookie);
				InetAddress[] inetLocs = new InetAddress[2];
				inetLocs[0] = InetAddress.getByName(strLocs[0]);
				inetLocs[1] = InetAddress.getByName(strLocs[1]);

				// RPC call
				String reply = sessionRead(sessNum, servNum, verNum, inetLocs);
				if (reply == null)
				{
					// send a dead cookie
					Cookie myDeadCookie = new Cookie(cookieName, "");
					myDeadCookie.setMaxAge(0);
					myDeadCookie.setValue(null);

					// send cookie back to client
					response.addCookie(myDeadCookie);

					// forward information to jsp page and display it
					request.setAttribute("loc", "line 331");
					request.getRequestDispatcher("/error.jsp").forward(request, response);
				}
				else
				{
					sess[0] = String.valueOf(sessNum);
					sess[1] = new String(servNum);
					ver = verNum;
					ver++;
					message = reply;
					long curTime = System.currentTimeMillis() / 1000;
					timeout = curTime + SESSION_TIMEOUT_SECS;
					loc[0] = SvrID;

					// choose a backup server from view and call SessionWrite()
					boolean rep = false;
					View myCopy = View.copy(view);
					while (rep == false)
					{
						String backup_ip = View.choose(myCopy);
						View.remove(myCopy, backup_ip);
						if (backup_ip.equals("0.0.0.0")) 
						{
							// nothing's in view
							loc[1] = "0.0.0.0";
							break;
						}
						else 
						{
							rep = sessionWrite(sessNum, servNum, ver, message, InetAddress.getByName(backup_ip));
							if (rep == true)
							{
								loc[1] = backup_ip;
								break;
							}
							else{
								RPCtimeout(InetAddress.getByName(backup_ip));
							}
						}
					}
					value = concatValue(sess, ver, loc);

					// store updated info to map (choose primary server)
					SessionTuple sessTup = new SessionTuple(Integer.valueOf(sess[0]), sess[1]);
					SessionState state = new SessionState(sessTup, ver, message, timeout);
					map.put(sessTup, state);

					// kill current cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					// reconstruct cookie
					c = new Cookie(cookieName, concatPrint(sess, ver, loc));
					c.setMaxAge(SESSION_TIMEOUT_SECS);		// set timeout!!!
					c.setComment(message);

					// send cookie back to client
					response.addCookie(c);

					// forward information to jsp page
					request.setAttribute("myLocal", SvrID);
					request.setAttribute("myVal", concatPrint(sess, ver, loc));
					request.setAttribute("myMessage", c.getComment());
					request.setAttribute("myLocation", "Existing session found REMOTELY in " + SvrID);

					request.setAttribute("myExp", (int)(System.currentTimeMillis()/1000) + SESSION_TIMEOUT_SECS);
					request.setAttribute("myDis", (int)(System.currentTimeMillis()/1000) + SESSION_TIMEOUT_SECS + DISCARD_TIME_DELTA);
					request.setAttribute("myView", view.toString());
					request.getRequestDispatcher("/myServlet.jsp").forward(request, response);
				}

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

		// in the case of time-out, redirect to time-out page
		if (myCookie == null)
		{
			// forward information to jsp page and display it
			request.getRequestDispatcher("/timeout.jsp").forward(request, response);

			return;
		}

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

		// logout button
		if (action.equals("logout"))
		{
			// remove session info from map
			SessionTuple sessTup = new SessionTuple(getID(myCookie), getIP(myCookie));
			map.remove(sessTup);

			// kill the cookie
			myCookie.setMaxAge(0);
			myCookie.setValue(null);

			// send cookie back to client
			response.addCookie(myCookie);

			// forward information to jsp page and display it
			request.getRequestDispatcher("/logout.jsp").forward(request, response);

			return;
		}

		// check if SessionState is stored in local server
		// i.e. check server_primary or server_backup == server_local
		String[] locs = getLoc(myCookie);
		boolean flag = false;
		for (String s : locs)
		{
			// if the SessionState is stored in local server, simply reconstruct cookie and update it
			if (!flag && (SvrID).equals(s))
			{
				// replace and refresh buttons
				if (!action.equals("logout"))
				{
					int sid = getID(myCookie);
					sess[0] = String.valueOf(sid);
					sess[1] = getIP(myCookie);
					SessionState ss = map.get(new SessionTuple(sid, sess[1]));
					if (ss == null)
					{
						// forward information to jsp page and display it
						// send a dead cookie
						Cookie myDeadCookie = new Cookie(cookieName, "");
						myDeadCookie.setMaxAge(0);
						myDeadCookie.setValue(null);

						// send cookie back to client
						response.addCookie(myDeadCookie);
						request.setAttribute("loc", "line 478");
						request.getRequestDispatcher("/error.jsp").forward(request, response);
						return;
					}
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
					timeout = curTime + SESSION_TIMEOUT_SECS;

					loc[0] = SvrID;

					// choose a backup server from view and call SessionWrite()
					boolean reply = false;
					View myCopy = View.copy(view);
					while (reply == false)
					{
						String backup_ip = View.choose(myCopy);
						View.remove(myCopy, backup_ip);
						if (backup_ip.equals("0.0.0.0")) 
						{
							// nothing's in view
							loc[1] = "0.0.0.0";
							break;
						}
						else
						{
							reply = sessionWrite(sid, sess[1], ver, message, InetAddress.getByName(backup_ip));
							if (reply == true)
							{
								loc[1] = backup_ip;
								break;
							}
							else{
								RPCtimeout(InetAddress.getByName(backup_ip));
							}
						}
					}

					value = concatValue(sess, ver, loc);

					// store updated info to map (choose primary server)
					SessionTuple sessTup = new SessionTuple(sid, sess[1]);
					SessionState state = new SessionState(sessTup, ver, msg, timeout);
					map.replace(sessTup, state);

					// kill current cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					// reconstruct cookie
					c = new Cookie(cookieName,  concatPrint(sess, ver, loc));
					c.setMaxAge(SESSION_TIMEOUT_SECS);		// set timeout!!!
					c.setComment(msg);

					// send cookie back to client
					response.addCookie(c);

					// forward information to jsp page
					request.setAttribute("myLocal", SvrID);
					request.setAttribute("myVal", concatPrint(sess, ver, loc));
					request.setAttribute("myMessage", c.getComment());
					request.setAttribute("myLocation", "Existing session found LOCALLY in " + SvrID);
					request.setAttribute("myExp", (int)(System.currentTimeMillis()/1000) + SESSION_TIMEOUT_SECS);
					request.setAttribute("myDis", (int)(System.currentTimeMillis()/1000) + SESSION_TIMEOUT_SECS + DISCARD_TIME_DELTA);
					request.setAttribute("myView", view.toString());
					request.getRequestDispatcher("/myServlet.jsp").forward(request, response);
				}

				flag = true;
			}
		}
		// if the SessionState is stored in another server, execute RPC calls
		if (!flag)
		{
			// extract relevant values for sessionRead
			int sessNum = getID(myCookie);
			String servNum = getIP(myCookie);
			int verNum = getVer(myCookie);
			String[] strLocs = getLoc(myCookie);
			InetAddress[] inetLocs = new InetAddress[2];
			inetLocs[0] = InetAddress.getByName(strLocs[0]);
			inetLocs[1] = InetAddress.getByName(strLocs[1]);

			String reply = "";
			if (action.equals("refresh"))
			{
				// RPC call
				reply = sessionRead(sessNum, servNum, verNum, inetLocs);
				if (reply == null)
				{
					// send a dead cookie
					Cookie myDeadCookie = new Cookie(cookieName, "");
					myDeadCookie.setMaxAge(0);
					myDeadCookie.setValue(null);

					// send cookie back to client
					response.addCookie(myDeadCookie);

					// forward information to jsp page and display it
					request.setAttribute("loc", "line 583 " + SvrID + " " + strLocs[0] + " " + inetLocs[0] + " " 
							+ strLocs[1] + " " + inetLocs[1]);
					request.getRequestDispatcher("/error.jsp").forward(request, response);
					return;
				}
			}

			sess[0] = String.valueOf(sessNum);
			sess[1] = new String(servNum);
			ver = verNum;
			ver++;
			if (action.equals("replace"))
			{
				// retrieve message from form
				message = request.getParameter("message");
			}
			else if (action.equals("refresh"))
			{
				// retrieve message from cookie
				message = reply + "readOccurred";
			}
			long curTime = System.currentTimeMillis() / 1000;
			timeout = curTime + SESSION_TIMEOUT_SECS;
			loc[0] = SvrID;

			// choose a backup server from view and call SessionWrite()
			boolean rep = false;
			View myCopy = View.copy(view);
			while (rep == false)
			{
				String backup_ip = View.choose(myCopy);
				View.remove(myCopy, backup_ip);
				if (backup_ip.equals("0.0.0.0")) 
				{
					// nothing's in view
					loc[1] = "0.0.0.0";
					break;
				}
				else 
				{
					rep = sessionWrite(sessNum, servNum, ver, message, InetAddress.getByName(backup_ip));
					if (rep == true)
					{
						loc[1] = backup_ip;
						break;
					}
					else{
						RPCtimeout(InetAddress.getByName(backup_ip));
					}
				}
			}
			value = concatValue(sess, ver, loc);

			// store updated info to map (choose primary server)
			SessionTuple sessTup = new SessionTuple(Integer.valueOf(sess[0]), sess[1]);
			SessionState state = new SessionState(sessTup, ver, message, timeout);
			map.put(sessTup, state);

			// kill current cookie
			myCookie.setMaxAge(0);
			myCookie.setValue(null);
			// reconstruct cookie
			c = new Cookie(cookieName, concatPrint(sess, ver, loc));
			c.setMaxAge(SESSION_TIMEOUT_SECS);		// set timeout!!!
			c.setComment(message);

			// send cookie back to client
			response.addCookie(c);

			// forward information to jsp page
			request.setAttribute("myLocal", SvrID);
			request.setAttribute("myVal", concatPrint(sess, ver, loc));
			request.setAttribute("myMessage", c.getComment());
			request.setAttribute("myLocation", "Existing session found REMOTELY in " + SvrID);

			request.setAttribute("myExp", (int)(System.currentTimeMillis()/1000) + SESSION_TIMEOUT_SECS);
			request.setAttribute("myDis", (int)(System.currentTimeMillis()/1000) + SESSION_TIMEOUT_SECS + DISCARD_TIME_DELTA);
			request.setAttribute("myView", view.toString());
			request.getRequestDispatcher("/myServlet.jsp").forward(request, response);
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
			String val = "";
			val = c.getValue();
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
			String val = "";
			val = c.getValue();
			String[] tokens = val.split("_");

			return tokens[1];
		}
		else 
			return null;
	}
	// returns the version number of the Cookie c
	private int getVer(Cookie c)
	{
		if (c != null)
		{
			String val="";
			val = c.getValue();
			String[] tokens = val.split("_");

			return Integer.valueOf(tokens[2]);
		}
		else 
			return -1;
	}
	// returns the locations (primary, backup ips)
	private String[] getLoc(Cookie c)
	{
		String[] ret = new String[2];
		if (c != null)
		{
			String val = "";
			val = c.getValue();
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

	private String concatPrint(String[] sess, int ver, String[] loc) {
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
			addr = InetAddress.getByName(SvrID);
		} 
		catch (UnknownHostException e) 
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return addr;
	}

	// sessionRead, sent by this server to multiple remote servers
	// give an array of addresses and ports from which to read (returns the first result)
	// returns a String containing the message (if null, error was encountered)
	private String sessionRead(
			int sessNum, String serverId , int sessVersionNum, 
			InetAddress[] address) {
		try {
			DatagramSocket rpcSocket = new DatagramSocket();
			rpcSocket.setSoTimeout(TIMEOUT * 2); //TODO: 2 is the number of possible RPC calls made
			int newCallId = callId.getAndAdd(1);

			ByteBuffer bbuf = ByteBuffer.allocate(2*4 + 1 + 2*serverId.length()); //3 ints + 1 byte + 1 string
			bbuf.putInt(newCallId);
			bbuf.put(SESSIONREAD);
			bbuf.putInt(sessNum);
//			bbuf.putInt(sessVersionNum);
			bbuf.put(serverId.getBytes());
			byte[] outBuf = bbuf.array();
			String ret = sessionReadHelper(newCallId, rpcSocket, outBuf, address, 0, sessVersionNum);
			rpcSocket.close();
			return ret;
		} catch (SocketException e) {
			// DatagramSocket could not be opened
		}
		return null;
	}

	private String sessionReadHelper(int cid, DatagramSocket socket, byte[] outBuf, InetAddress[] address, int index, int version) {
		// failed on all calls
		if (index >= address.length) {
			return null;
		}
		DatagramPacket sendPkt = new DatagramPacket(outBuf, outBuf.length, address[index], PORT);
		try {
			socket.send(sendPkt);
			byte[] inBuf = new byte[512];
			DatagramPacket recvPkt = new DatagramPacket(inBuf, inBuf.length);
			ByteBuffer bbuf = null;
			int recvCallId = -1;
			do {
				recvPkt.setLength(inBuf.length);
				socket.receive(recvPkt);
				RPCReceive(recvPkt.getAddress());
				bbuf = ByteBuffer.wrap(inBuf);
				recvCallId = bbuf.getInt();
			} while(recvCallId != cid);
			int success = bbuf.getInt();
			if (success==version) {
				return new String(inBuf, 8, recvPkt.getLength()-8).trim();
			} else {
				return sessionReadHelper(cid, socket, outBuf, address, index+1, version);
			}
		} catch (SocketTimeoutException e){
			e.printStackTrace();
			RPCtimeout(address[index]);
			return sessionReadHelper(cid, socket, outBuf, address, index+1, version);
		}catch (IOException e) {
			//send failed or timeout
			e.printStackTrace();
			RPCtimeout(address[index]);
			return sessionReadHelper(cid, socket, outBuf, address, index+1, version);
		}
	}

	// sessionWrite to one remote server
	// returns whether the call was successful
	private boolean sessionWrite(
			int sessNum, String serverId, int sessVersionNum, 
			String msg, InetAddress address) {
		try {
			DatagramSocket rpcSocket = new DatagramSocket();
			rpcSocket.setSoTimeout(TIMEOUT);
			int newCallId = callId.getAndAdd(1);

			ByteBuffer bbuf = ByteBuffer.allocate(3*4 + 8 + 1 + 2*serverId.length() + 2 + 2*msg.length()); //3 ints + long + byte + string + char + string
			bbuf.putInt(newCallId);
			bbuf.put(SESSIONWRITE);
			bbuf.putInt(sessNum);
			bbuf.putInt(sessVersionNum);
			bbuf.putLong((System.currentTimeMillis()/1000) + DISCARD_TIME_DELTA + SESSION_TIMEOUT_SECS);
			bbuf.put(serverId.getBytes());
			bbuf.put("_".getBytes());
			bbuf.put(msg.getBytes());
			byte[] outBuf = bbuf.array();
			DatagramPacket sendPkt = new DatagramPacket(outBuf, outBuf.length, address, PORT);
			rpcSocket.send(sendPkt);
			byte[] inBuf = new byte[4]; // response contains callId
			DatagramPacket recvPkt = new DatagramPacket(inBuf, inBuf.length);
			int recvCallId = -1;
			do { //loop through responses
				recvPkt.setLength(inBuf.length);
				rpcSocket.receive(recvPkt);
				RPCReceive(recvPkt.getAddress());
				bbuf = ByteBuffer.wrap(inBuf);
				recvCallId = bbuf.getInt();
			} while(recvCallId != newCallId);
			rpcSocket.close();
			return true;
		} catch (SocketException e) {
			// DatagramSocket could not be opened
			e.printStackTrace();
		} catch (SocketTimeoutException e){
			RPCtimeout(address);
		} catch (IOException e) {
			//send failed or timeout
			RPCtimeout(address);
		}
		return false;
	}

	private View getView(InetAddress address) {
		try {
			DatagramSocket rpcSocket = new DatagramSocket();
			rpcSocket.setSoTimeout(TIMEOUT);
			int newCallId = callId.getAndAdd(1);

			ByteBuffer bbuf = ByteBuffer.allocate(4 + 1);
			bbuf.putInt(newCallId);
			bbuf.put(GETVIEW);
			byte[] outBuf = bbuf.array();
			DatagramPacket sendPkt = new DatagramPacket(outBuf, outBuf.length, address, PORT);
			rpcSocket.send(sendPkt);
			byte[] inBuf = new byte[512]; // response contains callId
			DatagramPacket recvPkt = new DatagramPacket(inBuf, inBuf.length);
			int recvCallId = -1;
			do { //loop through responses
				recvPkt.setLength(inBuf.length);
				rpcSocket.receive(recvPkt);
				RPCReceive(recvPkt.getAddress());
				bbuf = ByteBuffer.wrap(inBuf);
				recvCallId = bbuf.getInt();
			} while(recvCallId != newCallId);
			View recvView = new View();

			String allview = new String(inBuf, 4, recvPkt.getLength()-4);
			allview.trim();
			for (String s : allview.split("_")) {
				if (!s.trim().equals("")) {
					View.insert(recvView, s);
				}
			}

			rpcSocket.close();
			return recvView;
		} catch (SocketException e) {
			// DatagramSocket could not be opened
			e.printStackTrace();
		} catch (SocketTimeoutException e){
			//			e.printStackTrace();
			RPCtimeout(address);
		}catch (IOException e) {
			//send failed or timeout
			RPCtimeout(address);
			e.printStackTrace();
		}
		return null;
	}

	// to be called in the MyServlet constructor
	private void rpcServer() {
		Thread daemonThread = new Thread(new Runnable() {
			@Override
			public void run()
			{
				try {
					DatagramSocket rpcSocket = new DatagramSocket(PORT);
					while(true)
					{
						byte[] inBuf = new byte[512];
						DatagramPacket recvPkt = new DatagramPacket(inBuf, inBuf.length);
						try {
							rpcSocket.receive(recvPkt);
						} catch (IOException e) {
							e.printStackTrace();
						}
						RPCReceive(recvPkt.getAddress());
						InetAddress returnAddr = recvPkt.getAddress();
						int returnPort = recvPkt.getPort();

						ByteBuffer bbuf = ByteBuffer.wrap(inBuf);
						int cid = bbuf.getInt();
						byte opCode = bbuf.get();

						byte[] outBuf = null;
						if (opCode == SESSIONREAD) {
							int sessNum = bbuf.getInt();
//							int sessionVersionNum = bbuf.getInt();
							
							String serverIdAddr = new String(inBuf, 9, recvPkt.getLength()-9);
							serverIdAddr = serverIdAddr.trim();
							SessionTuple sessTup = new SessionTuple(sessNum, serverIdAddr);
							SessionState sessState = map.get(sessTup);
							
							String debugMessage = "";
							for (SessionTuple s : map.keySet()) {
								SessionState state = map.get(s);
								debugMessage += "num: " + state.sessionID.sessionNum + " sid: " + state.sessionID.serverId + "\n";
							}
							debugMessage += "request: " + sessTup.sessionNum + " " + sessTup.serverId;
							if (sessState != null) {
								bbuf = ByteBuffer.allocate(4 + 4 + sessState.message.length()*2);
								bbuf.putInt(cid);
								bbuf.putInt(sessState.version);
								for (byte b : sessState.message.getBytes()) {
									bbuf.put(b);
								}

							} else {
								bbuf = ByteBuffer.allocate(4 + 4);
								bbuf.putInt(cid);
								bbuf.putInt(-1);
							}
							outBuf = bbuf.array();
						} else if (opCode==SESSIONWRITE) {
							int sessNum = bbuf.getInt();
							//							bbuf.getInt(); // increment by four bytes (the same four used to make the serverId below)
							int sessionVersionNum = bbuf.getInt();
							long discardTime = bbuf.getLong();
							
							String all = new String(inBuf, 21 , recvPkt.getLength()-21);
							all = all.trim();
							String[] sid_and_msg = all.split("_", 2);
							String serverIdAddr = sid_and_msg[0];
							String msg = sid_and_msg[1];

							SessionTuple sessTup = new SessionTuple(sessNum, serverIdAddr);
							SessionState sessState = new SessionState(sessTup, sessionVersionNum, msg, discardTime);
							map.put(sessTup, sessState);

							bbuf = ByteBuffer.allocate(4);
							bbuf.putInt(cid);
							outBuf = bbuf.array();
						} else if (opCode==GETVIEW) {
							HashSet<String> viewips = View.getIPs(view);
							int totallength = 0;
							for (String s : viewips) {
								totallength += s.length()*2 + 1;
							}
							bbuf = ByteBuffer.allocate(4 + totallength);
							bbuf.putInt(cid);
							for (String s : viewips) {
								bbuf.put(s.getBytes());
								bbuf.put("_".getBytes());
							}
							outBuf = bbuf.array();
						}
						DatagramPacket sendPkt = new DatagramPacket(outBuf, outBuf.length, returnAddr, returnPort);
						try {
							rpcSocket.send(sendPkt);
						} catch (IOException e) {
							RPCtimeout(returnAddr);
							e.printStackTrace();
						}
					}
				} catch (SocketException e) {
					// DatagramSocket could not be opened
					e.printStackTrace();
				}
			}
		});
		daemonThread.setDaemon(true);	// making this thread daemon
		daemonThread.start();
	}

	static String inetaddrToString(InetAddress addr) {
		return new String(addr.getAddress());
	}

	//Basic view rules
	public void RPCtimeout(InetAddress addr){
		String ipAddress = addr.getHostAddress();
		View.remove(view, ipAddress);
	}

	public void RPCReceive(InetAddress addr){
		String ipAddress = addr.getHostAddress();
		View.insert(view, ipAddress);
	}

	//Gossip Protocol Method
	public void gossip(){
		Thread daemonThread = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				Random generator = new Random();
				View temp;
				while(true)
				{
					temp = null;
					while (temp == null)
					{
						String ip = View.choose(view);
						if (ip.equals("0.0.0.0")) 
						{
							// nothing's in view
							temp = new View();
							break;
						}
						else
						{
							try {
								temp = getView(InetAddress.getByName(ip));
							} catch (UnknownHostException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
					}
					View.union(temp, view);
					View.remove(temp, SvrID);
					View.shrink(temp, ViewSz);
					view = temp;
					try {
						Thread.sleep((GOSSIP_MSECS/2) + generator.nextInt(GOSSIP_MSECS));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}
		});
		daemonThread.setDaemon(true);	// making this thread daemon
		daemonThread.start();
	}

	//Bootstrap method
	public void bootstrap(){
		Thread daemonThread = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				while(true)
				{
					View temp = ViewDB.readSDBView(ViewSz);
					View.remove(temp, SvrID);
					View.union(temp, view);
					View.shrink(temp, ViewSz);
					view = View.copy(temp);

					View.insert(temp, SvrID);
					View.shrink(temp, ViewSz);
					ViewDB.writeSDBView(temp, ViewSz);
					try {
						Thread.sleep(BOOTSTRAP_MSECS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}
		});
		daemonThread.setDaemon(true);	// making this thread daemon
		daemonThread.start();
	}
}
