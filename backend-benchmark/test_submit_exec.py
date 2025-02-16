import requests
import json
import sys


def login_user(base_url, username, password):
    url = f"{base_url}/api/cheese-auth/users/auth/login"
    payload = {"username": username, "password": password}
    headers = {"Content-Type": "application/json"}
    response = requests.post(url, json=payload, headers=headers)
    # print(response.json())
    return response.json()["data"]["accessToken"], response.json()["data"]["user"]["id"]


def submit_code(base_url, token, src):
    url = f"{base_url}/api/sandbox/submit"
    files = {"src": ("code.cpp", src)}
    headers = {"Authorization": f"Bearer {token}"}
    response = requests.post(url, files=files, headers=headers)
    # print(response.json())
    return response.json()


def exec_code(base_url, token, user_ids, input_data):
    url = f"{base_url}/api/sandbox/exec"
    payload = {"userIds": user_ids, "input": input_data}
    headers = {"Content-Type": "application/json", "Authorization": f"Bearer {token}"}
    response = requests.post(url, json=payload, headers=headers)
    # print(response.json())
    return response.json()


def main():
    base_url = sys.argv[1] if len(sys.argv) > 1 else "http://localhost"
    username = input("Enter username: ")
    password = input("Enter password: ")

    token, user_id = login_user(base_url, username, password)

    # Test submit with error
    src_with_error = """
    #include <iostream>
    
    int main() {
        std::cout << "Hello world!" << std::endl
        return 0;
    }
    """
    result = submit_code(base_url, token, src_with_error)
    assert result["data"]["success"] == False
    assert "error" in result["data"]["diagnose"]

    # Test submit with warning
    src_with_warning = """
    #include <iostream>
    #warning "This is a warning."
    
    int main() {
        std::cout << "Hello world!" << std::endl;
        return 0;
    }
    """
    result = submit_code(base_url, token, src_with_warning)
    assert result["data"]["success"] == True
    assert "warning" in result["data"]["diagnose"]

    # Test submit with no warning
    src_no_warning = """
    #include <iostream>
    
    int main() {
        std::cout << "Hello world!" << std::endl;
        return 0;
    }
    """
    result = submit_code(base_url, token, src_no_warning)
    assert result["data"]["success"] == True
    assert result["data"]["diagnose"] == ""

    # Test exec single success
    result = exec_code(base_url, token, [user_id], "test input")
    assert result["code"] == 200
    assert result["data"][0]["output"] == "Hello world!\n"
    assert isinstance(result["data"][0]["sandbox"], str)
    assert result["data"][0]["error"] is None

    # Test exec multiple success
    user_ids = [user_id] * 1000
    result = exec_code(base_url, token, user_ids, "test input")
    assert result["code"] == 200
    assert result["data"][0]["output"] == "Hello world!\n"
    assert isinstance(result["data"][0]["sandbox"], str)
    assert result["data"][0]["error"] is None
    assert result["data"][-1]["output"] == "Hello world!\n"
    assert isinstance(result["data"][-1]["sandbox"], str)
    assert result["data"][-1]["error"] is None

    # Test submit with wrong return code
    src_wrong_return = """
    #include <iostream>
    
    int main() {
        std::cout << "Hello world!" << std::endl;
        return 1;
    }
    """
    result = submit_code(base_url, token, src_wrong_return)
    assert result["data"]["success"] == True

    # Test exec single failed
    result = exec_code(base_url, token, [user_id], "test input")
    assert result["code"] == 200
    assert result["data"][0]["output"] == "Hello world!\n"
    assert isinstance(result["data"][0]["sandbox"], str)
    assert result["data"][0]["error"]["error"]["name"] == "ExecutionError"
    assert result["data"][0]["error"]["error"]["data"]["exitCode"] == 1

    # Test exec multiple failed
    result = exec_code(base_url, token, user_ids, "test input")
    assert result["code"] == 200
    assert result["data"][0]["output"] == "Hello world!\n"
    assert isinstance(result["data"][0]["sandbox"], str)
    assert result["data"][0]["error"]["error"]["name"] == "ExecutionError"
    assert result["data"][0]["error"]["error"]["data"]["exitCode"] == 1
    assert result["data"][-1]["output"] == "Hello world!\n"
    assert isinstance(result["data"][-1]["sandbox"], str)
    assert result["data"][-1]["error"]["error"]["name"] == "ExecutionError"
    assert result["data"][-1]["error"]["error"]["data"]["exitCode"] == 1


if __name__ == "__main__":
    main()
