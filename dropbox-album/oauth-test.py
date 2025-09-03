import base64
import hashlib
import os
import urllib.parse
import webbrowser
from http.server import HTTPServer, BaseHTTPRequestHandler
import threading
import requests

# ==== CONFIGURATION ====
CLIENT_ID = "qb3joeqf66gltdt"
REDIRECT_URI = "http://localhost:8080/callback"
TOKEN_URL = "https://api.dropboxapi.com/oauth2/token"

# ==== PKCE GENERATION ====
def generate_code_verifier():
    return base64.urlsafe_b64encode(os.urandom(40)).rstrip(b'=').decode('utf-8')

def generate_code_challenge(verifier):
    challenge = hashlib.sha256(verifier.encode('utf-8')).digest()
    return base64.urlsafe_b64encode(challenge).rstrip(b'=').decode('utf-8')

code_verifier = generate_code_verifier()
code_challenge = generate_code_challenge(code_verifier)

# ==== SIMPLE HTTP SERVER ====
auth_code = None

class OAuthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        global auth_code
        parsed_path = urllib.parse.urlparse(self.path)
        if parsed_path.path == "/callback":
            query = urllib.parse.parse_qs(parsed_path.query)
            if "code" in query:
                auth_code = query["code"][0]
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"<h1>Authorization complete. You can close this window.</h1>")
            else:
                self.send_response(400)
                self.end_headers()
                self.wfile.write(b"<h1>Missing code parameter</h1>")

# Start server in thread
def run_server():
    httpd = HTTPServer(("localhost", 8080), OAuthHandler)
    httpd.handle_request()

thread = threading.Thread(target=run_server)
thread.start()

# ==== BUILD AUTHORIZATION URL ====
params = {
    "client_id": CLIENT_ID,
    "redirect_uri": REDIRECT_URI,
    "response_type": "code",
    "code_challenge": code_challenge,
    "code_challenge_method": "S256",
    "token_access_type": "offline"
}

auth_url = "https://www.dropbox.com/oauth2/authorize?" + urllib.parse.urlencode(params)
print("Opening browser to authorize Dropbox access...")
print("URL:", auth_url)
webbrowser.open(auth_url)

# Wait for auth_code
thread.join()

if not auth_code:
    print("Authorization failed.")
    exit(1)

# ==== EXCHANGE CODE FOR TOKEN ====
data = {
    "code": auth_code,
    "grant_type": "authorization_code",
    "client_id": CLIENT_ID,
    "redirect_uri": REDIRECT_URI,
    "code_verifier": code_verifier
}

print("\nExchanging code for token...")
print("URL:", TOKEN_URL)
print("Data:")
for key, value in data.items():
    print(f"  {key}: {value}")
response = requests.post(TOKEN_URL, data=data)
print("\n✅ Token response:")
print(response.json())
