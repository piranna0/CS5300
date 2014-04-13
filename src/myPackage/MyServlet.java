package myPackage;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.net.UnknownHostException;
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
	public static int SESSION_TIMEOUT_SECS = 15;
	public static int DISCARD_TIME_DELTA = 1;
	private static AtomicInteger callId = new AtomicInteger();
	private final byte SESSIONREAD = 0; // operation code
	private final byte SESSIONWRITE = 1; // operation code
	private final int PORT = 5300;
	private String SvrID;

	private static int GOSSIP_MSECS = 12000;
	private static int BOOTSTRAP_MSECS = 12000;

	private View view = new View();

	// Session State table, aka hash map used to store session information
	// K: sessionID, V: SessionState
	private ConcurrentHashMap<SessionTuple, SessionState> map = new ConcurrentHashMap<SessionTuple, SessionState>();

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
					//					System.out.println("gc:" + map.keySet().size() + " session(s)");
					for (SessionTuple tup : map.keySet())
					{
						SessionState state = map.get(tup);
						//						System.out.println("gc:" + tup.serverId + "/" + tup.sessionNum + " has " + (state.timeout - (int)(System.currentTimeMillis()/1000)) + " seconds left");
						if (state.timeout < (int)(System.currentTimeMillis()/1000))
						{
							SessionState s = map.remove(tup);
							System.out.println("gc:removed <" + tup.serverId + "/" + tup.sessionNum + ", " + s.sessionID.serverId + ">");
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

		try {
			SvrID = inetaddrToString(InetAddress.getLocalHost());
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

        garbageCollector();   
        rpcServer();
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
		if (myCookie == null)
		{
			int sid = sessionID.getAndAdd(1);
			sess[0] = String.valueOf(sid);
			sess[1] = inetaddrToString(local_ip);
			ver = 1;
			message = "Hello, User!";
			long curTime = System.currentTimeMillis() / 1000;
			timeout = curTime + SESSION_TIMEOUT_SECS;

			loc[0] = SvrID;

			// choose a backup server from view and call SessionWrite()
			// TODO: debug dis
			boolean reply = false;
			View myCopy = View.copy(view);
			while (reply == false)
			{
				String backup_ip = View.choose(myCopy);
				if (backup_ip == null) 
				{
					// nothing's in view
					loc[1] = null;
					break;
				}
				else
				{
					reply = sessionWrite(sid, sess[1].getBytes(), ver, message, InetAddress.getByAddress(backup_ip.getBytes()));
					if (reply == true)
					{
						loc[1] = backup_ip;
						break;
					}
				}
			}

			value = concatValue(sess, ver, loc);

			// store new info to map
			SessionTuple sessTup = new SessionTuple(sid, loc[0]);
			SessionState state = new SessionState(sessTup, ver, message, timeout);
			map.put(sessTup, state);

			// construct cookie
			c = new Cookie(cookieName, URLEncoder.encode(value,"UTF-8"));
			c.setMaxAge(SESSION_TIMEOUT_SECS);	// set timeout!!!
			c.setComment(message);

			// send cookie back to client
			response.addCookie(c);

			// forward information to jsp page
			request.setAttribute("myVal", URLDecoder.decode(c.getValue(),"UTF-8"));
			request.setAttribute("myMessage", c.getComment());
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
				if (!flag && (SvrID).equals(s))
				{
					int sid = getID(myCookie);
					sess[0] = String.valueOf(sid);
					sess[1] = getIP(myCookie);
					SessionState ss = map.get(new SessionTuple(sid, sess[1]));
					if (ss == null)
						throw new ServletException("Current session has timed out.");
					ver = ss.version;
					ver++;
					message = ss.message;
					long curTime = System.currentTimeMillis() / 1000;
					timeout = curTime + SESSION_TIMEOUT_SECS;
					loc[0] = SvrID;

					// choose a backup server from view and call SessionWrite()
					// TODO: debug dis
					boolean reply = false;
					View myCopy = View.copy(view);
					while (reply == false)
					{
						String backup_ip = View.choose(myCopy);
						if (backup_ip == null) 
						{
							// nothing's in view
							loc[1] = null;
							break;
						}
						else 
						{
							reply = sessionWrite(sid, sess[1].getBytes(), ver, message, InetAddress.getByAddress(backup_ip.getBytes()));
							if (reply == true)
							{
								loc[1] = backup_ip;
								break;
							}
						}
					}
					value = concatValue(sess, ver, loc);

					// store updated info to map (choose primary server)
					SessionTuple sessTup = new SessionTuple(sid, loc[0]);
					SessionState state = new SessionState(sessTup, ver, message, timeout);
					map.replace(sessTup, state);

					// kill current cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					// reconstruct cookie
					c = new Cookie(cookieName, URLEncoder.encode(value,"UTF-8"));
					c.setMaxAge(SESSION_TIMEOUT_SECS);		// set timeout!!!
					c.setComment(message);

					// send cookie back to client
					response.addCookie(c);

					// forward information to jsp page
					request.setAttribute("myVal", URLDecoder.decode(c.getValue(),"UTF-8"));
					request.setAttribute("myMessage", c.getComment());
					request.getRequestDispatcher("/myServlet.jsp").forward(request, response);

					flag = true;
				}
			}
			// if the SessionState is stored in another server, execute RPC calls
			if (!flag)
			{
				// extract relevant values for sessionRead
				int sessNum = getID(myCookie);
				byte[] servNum = getIP(myCookie).getBytes();
				int verNum = getVer(myCookie);
				String[] strLocs = getLoc(myCookie);
				InetAddress[] inetLocs = new InetAddress[2];
				inetLocs[0] = InetAddress.getByAddress(strLocs[0].getBytes());
				inetLocs[1] = InetAddress.getByAddress(strLocs[1].getBytes());

				// RPC call
				String reply = sessionRead(sessNum, servNum, verNum, inetLocs);
				if (reply == null)
				{
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
					// TODO: debug dis
					boolean rep = false;
					View myCopy = View.copy(view);
					while (rep == false)
					{
						String backup_ip = View.choose(myCopy);
						if (backup_ip == null) 
						{
							// nothing's in view
							loc[1] = null;
							break;
						}
						else 
						{
							rep = sessionWrite(sessNum, servNum, ver, message, InetAddress.getByAddress(backup_ip.getBytes()));
							if (rep == true)
							{
								loc[1] = backup_ip;
								break;
							}
						}
					}
					value = concatValue(sess, ver, loc);

					// store updated info to map (choose primary server)
					SessionTuple sessTup = new SessionTuple(Integer.valueOf(sess[0]), loc[0]);
					SessionState state = new SessionState(sessTup, ver, message, timeout);
					map.put(sessTup, state);

					// kill current cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					// reconstruct cookie
					c = new Cookie(cookieName, URLEncoder.encode(value, "UTF-8"));
					c.setMaxAge(SESSION_TIMEOUT_SECS);		// set timeout!!!
					c.setComment(message);

					// send cookie back to client
					response.addCookie(c);

					// forward information to jsp page
					request.setAttribute("myVal", URLDecoder.decode(c.getValue(),"UTF-8"));
					request.setAttribute("myMessage", c.getComment());
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
					timeout = curTime + SESSION_TIMEOUT_SECS;

					loc[0] = SvrID;

					// choose a backup server from view and call SessionWrite()
					// TODO: debug dis
					boolean reply = false;
					View myCopy = View.copy(view);
					while (reply == false)
					{
						String backup_ip = View.choose(myCopy);
						if (backup_ip == null) 
						{
							// nothing's in view
							loc[1] = null;
							break;
						}
						else
						{
							reply = sessionWrite(sid, sess[1].getBytes(), ver, message, InetAddress.getByAddress(backup_ip.getBytes()));
							if (reply == true)
							{
								loc[1] = backup_ip;
								break;
							}
						}
					}

					value = concatValue(sess, ver, loc);

					// store updated info to map (choose primary server)
					SessionTuple sessTup = new SessionTuple(sid, loc[0]);
					SessionState state = new SessionState(sessTup, ver, msg, timeout);
					map.replace(sessTup, state);

					// kill current cookie
					myCookie.setMaxAge(0);
					myCookie.setValue(null);
					// reconstruct cookie
					c = new Cookie(cookieName, URLEncoder.encode(value,"UTF-8"));
					c.setMaxAge(SESSION_TIMEOUT_SECS);		// set timeout!!!
					c.setComment(msg);

					// send cookie back to client
					response.addCookie(c);

					// forward information to jsp page
					request.setAttribute("myVal", URLDecoder.decode(c.getValue(),"UTF-8"));
					request.setAttribute("myMessage", c.getComment());
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
			byte[] servNum = getIP(myCookie).getBytes();
			int verNum = getVer(myCookie);
			String[] strLocs = getLoc(myCookie);
			InetAddress[] inetLocs = new InetAddress[2];
			inetLocs[0] = InetAddress.getByAddress(strLocs[0].getBytes());
			inetLocs[1] = InetAddress.getByAddress(strLocs[1].getBytes());

			String reply = "";
			if (action.equals("refresh"))
			{
				// RPC call
				reply = sessionRead(sessNum, servNum, verNum, inetLocs);
				if (reply == null)
				{
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
				message = reply;
			}
			long curTime = System.currentTimeMillis() / 1000;
			timeout = curTime + SESSION_TIMEOUT_SECS;
			loc[0] = getIP().getHostAddress();

			// choose a backup server from view and call SessionWrite()
			// TODO: debug dis
			boolean rep = false;
			View myCopy = View.copy(view);
			while (rep == false)
			{
				String backup_ip = View.choose(myCopy);
				if (backup_ip == null) 
				{
					// nothing's in view
					loc[1] = null;
					break;
				}
				else 
				{
					rep = sessionWrite(sessNum, servNum, ver, message, InetAddress.getByAddress(backup_ip.getBytes()));
					if (rep == true)
					{
						loc[1] = backup_ip;
						break;
					}
				}
			}
			value = concatValue(sess, ver, loc);

			// store updated info to map (choose primary server)
			SessionTuple sessTup = new SessionTuple(Integer.valueOf(sess[0]), loc[0]);
			SessionState state = new SessionState(sessTup, ver, message, timeout);
			map.put(sessTup, state);

			// kill current cookie
			myCookie.setMaxAge(0);
			myCookie.setValue(null);
			// reconstruct cookie
			c = new Cookie(cookieName, URLEncoder.encode(value,"UTF-8"));
			c.setMaxAge(SESSION_TIMEOUT_SECS);		// set timeout!!!
			c.setComment(message);

			// send cookie back to client
			response.addCookie(c);

			// forward information to jsp page
			request.setAttribute("myVal", URLDecoder.decode(c.getValue(),"UTF-8"));
			request.setAttribute("myMessage", c.getComment());
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
			try {
				val = URLDecoder.decode(c.getValue(),"UTF-8");
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
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
			try {
				val = URLDecoder.decode(c.getValue(),"UTF-8");
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			String[] tokens = val.split("_");

			return String.valueOf(tokens[1]);
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
			try {
				val = URLDecoder.decode(c.getValue(),"UTF-8");
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
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
			try {
				val = URLDecoder.decode(c.getValue(), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
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
			addr = InetAddress.getByAddress(SvrID.getBytes());
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
			int sessNum, byte[] serverId , int sessVersionNum, 
			InetAddress[] address) {
		try {
			DatagramSocket rpcSocket = new DatagramSocket();
			int newCallId = callId.getAndAdd(1);

			ByteBuffer bbuf = ByteBuffer.allocate(17); //4 ints + 1 byte
			bbuf.putInt(newCallId);
			bbuf.put(SESSIONREAD);
			bbuf.putInt(sessNum);
			for (byte b : serverId) {
				bbuf.put(b);
			}
			bbuf.putInt(sessVersionNum);
			byte[] outBuf = bbuf.array();
			return sessionReadHelper(newCallId, rpcSocket, outBuf, address, 0);
		} catch (SocketException e) {
			// DatagramSocket could not be opened
			e.printStackTrace();
		}
		return null;
	}

	private String sessionReadHelper(int cid, DatagramSocket socket, byte[] outBuf, InetAddress[] address, int index) {
		// failed on all calls
		if (index > address.length) {
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
			byte success = bbuf.get();
			if (success==1) {
                return new String(inBuf, 5, recvPkt.getLength()-5);
			} else {
				return null;
			}
		} catch (IOException e) {
			//send failed or timeout
			e.printStackTrace();
			RPCtimeout(address[index]);
			return sessionReadHelper(cid, socket, outBuf, address, index+1);
		}
	}

	// sessionWrite to one remote server
	// returns whether the call was successful
	private boolean sessionWrite(
			int sessNum, byte[] serverId, int sessVersionNum, 
			String msg, InetAddress address) {
		try {
			DatagramSocket rpcSocket = new DatagramSocket();
			int newCallId = callId.getAndAdd(1);

			ByteBuffer bbuf = ByteBuffer.allocate(4*4 + 8 + 1 + 2*msg.length()); //5 ints + 1 byte + string
			bbuf.putInt(newCallId);
			bbuf.put(SESSIONWRITE);
			bbuf.putInt(sessNum);
			for (byte b : serverId) {
				bbuf.put(b);
			}
			bbuf.putInt(sessVersionNum);
			bbuf.putLong((System.currentTimeMillis()/1000) + DISCARD_TIME_DELTA + SESSION_TIMEOUT_SECS);
			for (byte b : msg.getBytes()) {
				bbuf.put(b);
			}
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
			return true;
		} catch (SocketException e) {
			// DatagramSocket could not be opened
			e.printStackTrace();
		} catch (IOException e) {
			//send failed or timeout
			RPCtimeout(address);
			e.printStackTrace();
		}
		return false;
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
                        int sessNum = bbuf.getInt();
                        bbuf.getInt(); // increment by four bytes (the same four used to make the serverId below)
                        String serverIdAddr = new String(inBuf, 8, 4);
                        int sessionVersionNum = bbuf.getInt();
                        SessionTuple sessTup = new SessionTuple(sessNum, serverIdAddr);
                        SessionState sessState = null;

						byte[] outBuf = null;
						if (opCode == SESSIONREAD) {
							sessState = map.get(sessTup);
							if (sessState != null) {
                                bbuf = ByteBuffer.allocate(4 + 1 + sessState.message.length()*2);
                                bbuf.putInt(cid);
								bbuf.put((byte) 1);
                                for (byte b : sessState.message.getBytes()) {
                                        bbuf.put(b);
                                }
							} else {
                                bbuf = ByteBuffer.allocate(4 + 1);
                                bbuf.putInt(cid);
								bbuf.put((byte) 0);
							}
							outBuf = bbuf.array();
						} else if (opCode==SESSIONWRITE) {
							long discardTime = bbuf.getLong();
							String msg = new String(inBuf, 25, recvPkt.getLength()-25);
							sessState = new SessionState(sessTup, sessionVersionNum, msg, discardTime);
							map.put(sessTup, sessState);

							bbuf = ByteBuffer.allocate(4);
							bbuf.putInt(cid);
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

	private String inetaddrToString(InetAddress addr) {
		return new String(addr.getAddress());
	}

	//Basic view rules
	public void RPCtimeout(InetAddress addr){
		String ipAddress = inetaddrToString(addr);
		View.remove(view, ipAddress);
	}

	public void RPCReceive(InetAddress addr){
		String ipAddress = inetaddrToString(addr);
		View.insert(view, ipAddress);
	}

	//Gossip Protocol Method
	public void gossip(InetAddress addr){
		Thread daemonThread = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				Random generator = new Random();
				while(true)
				{
					//		TODO: Need RPC call for GetView written
					//		View temp = GetView(addr);
					View temp = new View();
					View.union(temp, view);
					View.remove(temp, SvrID);
					View.shrink(temp, ViewSz);
					view = temp;
					try {
						Thread.sleep((GOSSIP_MSECS/2) + generator.nextInt(GOSSIP_MSECS));		// 10 seconds
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();		// restore interrupted status
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
					View temp = ViewDB.readSDBView();
					View.remove(temp, SvrID);
					View.union(temp, view);
					View.shrink(temp, ViewSz);
					view = View.copy(temp);
					View.insert(temp, SvrID);
					View.shrink(temp, ViewSz);
					ViewDB.writeSDBView(temp);
					try {
						Thread.sleep(BOOTSTRAP_MSECS);		// 10 seconds
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();		// restore interrupted status
					}
				}
			}
		});
		daemonThread.setDaemon(true);	// making this thread daemon
		daemonThread.start();
	}

}
