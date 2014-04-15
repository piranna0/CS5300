ay226 Ansha Yu
gws55 Gene Shin 
jkf49 James Feher


There are 5 files in our source directory:
MyServlet.java: the servlet code. Handles server requests, creates and stores session state data, executes RPC calls when necessary, spawns the four daemon threads (garbage collector, bootstrap updater, gossiper, and the RPC server) and returns cookies and the servlet web-page to the client.
SessionState.java: defines the session state objects that will be stored in the hash maps.
SessionTuple.java: defines the session ID for a SessionState object. i.e. the <sessionID, serverIP> tuple that uniquely determines a session state.
View.java: defines the views for the servers as well as the methods required for bootstrapping and gossiping.
ViewDB.java: sets up the AmazonSimpleDB that stores the bootstrap and defines read and write methods.

And there are 5 files in our webcontent directory:
error.jsp: redirects to this page on error.
index.html: the default page that redirects to the servlet page.
logout.jsp: redirects to this page on log out.
myServlet.jsp: the main front-end of our web site from project 1a with additional information displayed (e.g. the server's view and the session data's primary/backup servers)
timeout.jsp: redirects to this page on time out.

Cookie format: sessionID_serverIP_versionNum_serverPrimary_serverBackup
where <sessionID, serverIP> is the unique identifier for session states, and serverPrimary and serverBackup are primary and backup servers where the session state is stored.

RPC messages format: 
The methods sessionRead(...), sessionWrite(...), and getView(...) are wrappers for the corresponding RPC message calls that are in the same format as that specified on the project writeup
sessionRead takes the <sessionId, serverIP> tuple, version number and IP addresses (the primary and backup servers) of the requested session state.
sessionRead returns what's stored in the message field and version number of the requested session state if it exists (error is version = -1)
sessionWrite takes the <sessionID, serverIP> tuple, version number, message, and IP address of the server that contains the session state that you wish to replace. 
sessionWrite returns true if the session state is successfully replaced in the remote server, otherwise returns false. 
getView takes in the address of the server whose view is requested
getView returns a View object containing the IPs of the servers in the requested server's View


Elastic Beanstalk setup procedure:
- create an instance of Elastic Beanstalk environment on AWS management console, running on 64bit Amazon Linux
- on eclipse, create a new server on Elastic Beanstalk for Tomcat 7 and connect to the environment above 
- add your servlet project to the server
- run your project on the server

