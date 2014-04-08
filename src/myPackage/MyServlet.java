package myPackage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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
		Cookie myCookie = FindCookie(cookies, cookieName);		// current client's cookie
		
		// client's first request, construct new cookie and new SessionState
		int sess, ver;
		long timeout;
		String message, loc, value;
		Cookie c;
		if (!map.containsKey(GetID(myCookie)))
		{
			sess = sessionID;
			ver = 1;
			message = "Hello, User!";
			long curTime = System.currentTimeMillis() / 1000;
			timeout = curTime + expiry;
			loc = location;
			value = ConcatValue(sess, ver, timeout, loc);
			
			// store new info to map
			SessionState state = new SessionState(sess, ver, message, timeout);
			map.put(sess, state);
			
			// construct cookie
			c = new Cookie(cookieName, value);
			c.setVersion(ver);
			c.setMaxAge(expiry);
			c.setPath(loc);
			c.setComment(message);
			
			sessionID++;
		}
		// otherwise, reconstruct cookie and update SessionState
		else
		{
			sess = GetID(myCookie);
			SessionState ss = map.get(sess);
			if (ss == null)
				throw new ServletException("Current session has timed out.");
			ver = ss.version;
			ver++;
			message = ss.message;
			long curTime = System.currentTimeMillis() / 1000;
			timeout = curTime + expiry;
			loc = location;
			value = ConcatValue(sess, ver, timeout, loc);
			
			// store updated info to map
			SessionState state = new SessionState(sess, ver, message, timeout);
			map.replace(sess, state);
			
			// reconstruct cookie
			myCookie.setMaxAge(0);		// kill current cookie
			myCookie.setValue(null);
			c = new Cookie(cookieName, value);
			c.setVersion(ver);
			c.setMaxAge(expiry);
			c.setPath(loc);
			c.setComment(message);
		}

		// send cookie back to client
		response.addCookie(c);
		
		// forward information to jsp page
		request.setAttribute("myVal", c.getValue());
		request.setAttribute("myMessage", c.getComment());
        request.getRequestDispatcher("/myServlet.jsp").forward(request, response);
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException 
	{
		Cookie[] cookies = request.getCookies();
		Cookie myCookie = FindCookie(cookies, cookieName);		// current client's cookie
		
		String action = request.getParameter("action");
		String message = null;
		
		// replace and refresh buttons
		if (!action.equals("logout"))
		{
			// update cookie parameters
			int sess, ver;
			long timeout;
			String msg, loc, value;
			Cookie c;
			sess = GetID(myCookie);
			SessionState ss = map.get(sess);
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
			loc = location;
			value = ConcatValue(sess, ver, timeout, loc);
			
			// store updated info to map
			SessionState state = new SessionState(sess, ver, msg, timeout);
			map.replace(sess, state);
			
			// reconstruct cookie
			myCookie.setMaxAge(0);		// kill current cookie
			myCookie.setValue(null);
			c = new Cookie(cookieName, value);
			c.setVersion(ver);
			c.setMaxAge(expiry);
			c.setPath(loc);
			c.setComment(msg);
			
			// send cookie back to client
			response.addCookie(c);
			
			// forward information to jsp page and display it
			request.setAttribute("myVal", c.getValue());
			request.setAttribute("myMessage", c.getComment());
	        request.getRequestDispatcher("/myServlet.jsp").forward(request, response);
		}
		// logout button
		else
		{
			// remove session info from map
			map.remove(GetID(myCookie));
			
			// kill the cookie
			myCookie.setMaxAge(0);
			myCookie.setValue(null);
			
			// send cookie back to client
			response.addCookie(myCookie);
			
			// forward information to jsp page and display it
	        request.getRequestDispatcher("/logout.jsp").forward(request, response);
		}
		
	}

	// search for cookie if it exists already, else return null
	private Cookie FindCookie(Cookie[] cookies, String name)
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
	private int GetID(Cookie c)
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
	
	// constructs the value string for session states
	private String ConcatValue(int sess, int ver, long timeout, String loc)
	{
		return String.valueOf(sess) + "_" + 
				String.valueOf(ver) + "_" + 
				String.valueOf(timeout) + "_" + 
				loc;
	}
	
}
