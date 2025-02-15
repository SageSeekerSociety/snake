import requests
import sys

def request_email_code(base_url, email):
    url = f"{base_url}/users/verify/email"
    payload = {"email": email}
    headers = {"Content-Type": "application/json"}
    response = requests.post(url, json=payload, headers=headers)
    return response.json()

def register_user(base_url, username, nickname, password, email, email_code):
    url = f"{base_url}/users"
    payload = {
        "username": username,
        "nickname": nickname,
        "password": password,
        "email": email,
        "emailCode": email_code
    }
    headers = {
        "Content-Type": "application/json"
    }
    response = requests.post(url, json=payload, headers=headers)
    return response.json()

if __name__ == "__main__":
    base_url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
    username = input("Enter username: ")
    nickname = input("Enter nickname: ")
    password = input("Enter password: ")
    email = input("Enter email: ")

    # Request email verification code
    email_code_response = request_email_code(base_url, email)
    print(email_code_response)
    email_code = input("Enter email verification code: ")

    result = register_user(base_url, username, nickname, password, email, email_code)
    print(result)
