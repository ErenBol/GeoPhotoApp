package com.example.geophotoapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
fun AuthScreen(auth: AuthRepository) {
    var register by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(if (register) "Create account" else "Log in")
        if (register) OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it) }
        Button(
            enabled = !busy && email.isNotBlank() && password.length >= 8 && (!register || username.isNotBlank()),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        auth.authenticate(email.trim(), password, if (register) username.trim() else null)
                    } catch (e: HttpException) {
                        error = when (e.code()) {
                            401 -> "Invalid email or password"
                            409 -> "Username or email already registered"
                            422 -> "Check your email, password, and username"
                            else -> "Server error (${e.code()})"
                        }
                    } catch (e: IOException) {
                        error = "Cannot reach the server. Check the backend connection."
                    } catch (e: Exception) {
                        error = e.message ?: "Authentication failed"
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Please wait…" else if (register) "Register" else "Log in") }
        OutlinedButton(onClick = { register = !register; error = null }) {
            Text(if (register) "Already have an account? Log in" else "Create an account")
        }
    }
}
