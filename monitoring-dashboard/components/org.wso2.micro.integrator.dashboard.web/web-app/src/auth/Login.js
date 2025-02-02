/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
import React, {useEffect, useState} from 'react';
import {Helmet} from "react-helmet";
import {withStyles} from '@material-ui/core/styles';
import Button from '@material-ui/core/Button';
import CssBaseline from '@material-ui/core/CssBaseline';
import TextField from '@material-ui/core/TextField';
import FormControlLabel from '@material-ui/core/FormControlLabel';
import Checkbox from '@material-ui/core/Checkbox';
import Typography from '@material-ui/core/Typography';
import Container from '@material-ui/core/Container';
import Box from '@material-ui/core/Box';
import Dialog from '@material-ui/core/Dialog';
import DialogActions from '@material-ui/core/DialogActions';
import DialogContent from '@material-ui/core/DialogContent';
import DialogContentText from '@material-ui/core/DialogContentText';
import DialogTitle from '@material-ui/core/DialogTitle';
import {Redirect} from 'react-router';
import { useAuthContext } from "@asgardeo/auth-react";
import AuthManager from './AuthManager'

const styles = theme => ({
    paper: {
        marginTop: theme.spacing(8),
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
    },
    avatar: {
        margin: theme.spacing(1),
        backgroundColor: theme.palette.secondary.main,
    },
    form: {
        width: '100%', // Fix IE 11 issue.
        marginTop: theme.spacing(1),
    },
    submit: {
        margin: theme.spacing(3, 0, 2),
    },
});

function Login(props){

    const [userName, setUserName] = useState('');
    const [password, setPassword] = useState('');
    const [authenticated, setAuthenticated] = useState(false);
    const [loginErrorMessage, setLoginErrorMessage] = useState('')
    const [loginError, setLoginError] = useState(false)
    const { signIn } = useAuthContext();

    useEffect(() => {
        initAuthenticationFlow();
    }, [])

    /**
     * Check if the user has already signed in and remember me is set
     */
    const initAuthenticationFlow = () => {
        if (!AuthManager.isLoggedIn()) {
            setAuthenticated(false)
        } else {
            setAuthenticated(true)
        }
    }

    const authenticate = (e)  => {

        e.preventDefault();

        AuthManager.authenticate(userName, password, true)
            .then(() => { setAuthenticated(true)})
            .catch((error) => {
                console.log("Authentication failed with error :: " + error);
                let errorMessage;
                if (error.response && error.response.status === 401) {
                    errorMessage = 'Incorrect username or password!';
                } else {
                    errorMessage = "Error occurred in communication. Please check server logs."
                }
                setUserName('')
                setPassword('')
                setLoginErrorMessage(errorMessage)
                setLoginError(true)

            });
    }

    const handleLoginErrorDialogClose = () => {
        setLoginError(false)
        setLoginErrorMessage('')
    }

    const renderDefaultLogin = () => {
        const { classes } = props;

        return (
            <Container component="main" maxWidth="xs">
                <Helmet>
                    <title>Login - Micro Integrator Dashboard</title>
                </Helmet>
                <CssBaseline />
                <div className={classes.paper}>
                    <img
                        alt='logo'
                        src='/logo-inverse.svg'
                        width={200}
                    />
                    <Box mt={4}>
                        <Typography component="h1" variant="h5">
                            Sign In
                        </Typography>
                    </Box>

                    <form className={classes.form} noValidate>
                        <TextField
                            variant="outlined"
                            margin="normal"
                            required
                            fullWidth
                            id="username"
                            label="Username"
                            name="username"
                            autoComplete="on"
                            value={userName}
                            onChange={(e) => { setUserName(e.target.value)}}
                            autoFocus
                        />
                        <TextField
                            variant="outlined"
                            margin="normal"
                            required
                            fullWidth
                            name="password"
                            label="Password"
                            type="password"
                            id="password"
                            value={password}
                            autoComplete="on"
                            onChange={(e) => {setPassword(e.target.value)}}
                        />
                        <FormControlLabel
                            control={<Checkbox value="remember" color="primary" />}
                            label="Remember me"
                        />
                        <Button
                            type="submit"
                            fullWidth
                            variant="contained"
                            color="primary"
                            className={classes.submit}
                            disabled={userName === '' || password === ''}
                            onClick={authenticate}
                        >
                            Sign In
                        </Button>
                        {window.sso.enable &&
                        <Button
                            type="button"
                            fullWidth
                            variant="contained"
                            color="primary"
                            onClick={ () => {
                                signIn(window.sso.authorizationRequestParams);
                            } }>
                            Sign In with SSO
                        </Button>
                        }
{/*                        <Grid container>
                            <Grid item xs>
                                <Link href="#" variant="body2">
                                    Forgot password?
                    </Link>
                            </Grid>
                            <Grid item>
                                <Link href="#" variant="body2">
                                    {"Don't have an account? Sign Up"}
                                </Link>
                            </Grid>
                        </Grid>*/}
                    </form>
                </div>
                <Box mt={8}>
                    <Typography variant="body2" color="textSecondary" align="center">
                        {`© 2005 - ${new Date().getFullYear()} WSO2 Inc. All Rights Reserved.`}
                    </Typography>
                </Box>
                <Dialog open={loginError} onClose={handleLoginErrorDialogClose}
                    aria-labelledby="alert-dialog-title" aria-describedby="alert-dialog-description">
                    <DialogTitle id="alert-dialog-title">{"Login Failed"}</DialogTitle>
                    <DialogContent dividers>
                        <DialogContentText id="alert-dialog-description">
                            {loginErrorMessage}
                        </DialogContentText>
                    </DialogContent>
                    <DialogActions>
                        <Button onClick={handleLoginErrorDialogClose} color="primary" autoFocus>
                            OK
                        </Button>
                    </DialogActions>
                </Dialog>
            </Container>

        );
    }

    if (authenticated) {
        return <Redirect to="/" />
    }
    return renderDefaultLogin();

}

export default withStyles(styles)(Login);
