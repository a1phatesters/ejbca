package se.anatom.ejbca.protocol;

import java.io.*;

import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Vector;

import javax.naming.InitialContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.bouncycastle.asn1.DERGeneralizedTime;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.ocsp.RevokedInfo;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.X509Extension;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.ocsp.*;

import org.apache.log4j.Logger;
import org.apache.commons.lang.StringUtils;

import se.anatom.ejbca.SecConst;
import se.anatom.ejbca.ca.caadmin.ICAAdminSessionLocal;
import se.anatom.ejbca.ca.caadmin.ICAAdminSessionLocalHome;
import se.anatom.ejbca.ca.caadmin.extendedcaservices.ExtendedCAServiceNotActiveException;
import se.anatom.ejbca.ca.caadmin.extendedcaservices.IllegalExtendedCAServiceRequestException;
import se.anatom.ejbca.ca.caadmin.extendedcaservices.OCSPCAServiceRequest;
import se.anatom.ejbca.ca.caadmin.extendedcaservices.OCSPCAServiceResponse;
import se.anatom.ejbca.ca.crl.RevokedCertInfo;
import se.anatom.ejbca.ca.exception.CADoesntExistsException;
import se.anatom.ejbca.ca.exception.SignRequestException;
import se.anatom.ejbca.ca.exception.SignRequestSignatureException;
import se.anatom.ejbca.ca.sign.ISignSessionLocal;
import se.anatom.ejbca.ca.sign.ISignSessionLocalHome;
import se.anatom.ejbca.ca.store.ICertificateStoreSessionLocalHome;
import se.anatom.ejbca.ca.store.ICertificateStoreSessionLocal;
import se.anatom.ejbca.log.Admin;
import se.anatom.ejbca.protocol.exception.MalformedRequestException;
import se.anatom.ejbca.util.Hex;
import se.anatom.ejbca.util.Base64;
import se.anatom.ejbca.util.CertTools;

/** 
 * Servlet implementing server side of the Online Certificate Status Protocol (OCSP)
 * For a detailed description of OCSP refer to RFC2560.
 * 
 * @author Thomas Meckel (Ophios GmbH)
 * @version  $Id: OCSPServlet.java,v 1.19 2004-01-01 19:52:18 anatom Exp $
 */
public class OCSPServlet extends HttpServlet {

    private static Logger m_log = Logger.getLogger(OCSPServlet.class);

    private ICertificateStoreSessionLocal m_certStore;
    private ICAAdminSessionLocal m_caadminsession;
    private ISignSessionLocal m_signsession = null;
    private Admin m_adm;

    private String m_sigAlg;
    private boolean m_reqMustBeSigned;
    private Collection m_cacerts = null;
    /** Cache time counter */
    private long m_certValidTo = 0;
    /** Cached list of cacerts is valid 5 minutes */
    private static final long VALID_TIME = 5 * 60 * 1000;
    /** String used to identify default responder id, used to generatwe responses when a request
     * for a certificate not signed by a CA on this server is received.
     */
    private String m_defaultResponderId;

    protected class OcspSigningMaterial {
        protected PrivateKey pk = null;
        protected X509Certificate[] certchain = null;
        public PrivateKey getPrivateKey() { return pk; }
        public X509Certificate [] getCertChain() { return certchain; }
         
    }
    /** Loads cacertificates but holds a cache so it's reloaded only every five minutes is needed.
    */
    protected synchronized void loadCertificates() 
        throws IOException {
        // Kolla om vi har en cachad collection och om den inte �r f�r gammal
        if ( m_cacerts != null && m_certValidTo > new Date().getTime() ) {
            return;
        }
        try {
            m_cacerts = m_certStore.findCertificatesByType(m_adm, SecConst.CERTTYPE_SUBCA + SecConst.CERTTYPE_ROOTCA, null);
            m_certValidTo = new Date().getTime() + VALID_TIME;
        } catch (Exception e) {
            m_log.error("Unable to load CA certificates from CA store.", e);
            throw new IOException(e.toString());
        }
    }

    protected X509Certificate findCAByHash(CertificateID certId, Collection certs) throws OCSPException {
        if (null == certId) {
            throw new IllegalArgumentException();
        }
        if (null == certs || certs.isEmpty()) {
            m_log.info("The passed certificate collection is empty.");
            return null;
        }
        Iterator iter = certs.iterator();
        while (iter.hasNext()) {
            X509Certificate cacert = (X509Certificate)iter.next();
            CertificateID issuerId = new CertificateID(certId.getHashAlgOID(), cacert, cacert.getSerialNumber());
            if (m_log.isDebugEnabled()) {
                m_log.debug("Comparing the following certificate hashes:\n"
                            + " Hash algorithm : '" + certId.getHashAlgOID() + "'\n"
                            + " CA certificate hashes\n"
                            + "      Name hash : '" + Hex.encode(issuerId.getIssuerNameHash()) + "'\n"
                            + "      Key hash  : '" + Hex.encode(issuerId.getIssuerKeyHash()) + "'\n"
                            + " OCSP certificate hashes\n"
                            + "      Name hash : '" + Hex.encode(certId.getIssuerNameHash()) + "'\n"
                            + "      Key hash  : '" + Hex.encode(certId.getIssuerKeyHash()) + "'\n");
            }
            if ( (issuerId.toASN1Object().getIssuerNameHash().equals(certId.toASN1Object().getIssuerNameHash())) 
                && (issuerId.toASN1Object().getIssuerKeyHash().equals(certId.toASN1Object().getIssuerKeyHash())) ) {
                m_log.debug("Found matching CA-cert with:\n"
                + "      Name hash : '" + Hex.encode(issuerId.getIssuerNameHash()) + "'\n"
                + "      Key hash  : '" + Hex.encode(issuerId.getIssuerKeyHash()) + "'\n");
                return cacert;
            }
        }
        m_log.debug("Did not find matching CA-cert for:\n"
        + "      Name hash : '" + Hex.encode(certId.getIssuerNameHash()) + "'\n"
        + "      Key hash  : '" + Hex.encode(certId.getIssuerKeyHash()) + "'\n");
        return null;
    }

    protected X509Certificate findCertificateBySubject(String subjectDN, Collection certs) 
    {
        if (certs == null || null == subjectDN) {
            throw new IllegalArgumentException();
        }

        if (null == certs || certs.isEmpty()) {
            m_log.info("The passed certificate collection is empty.");
            return null;
        }
        String dn = CertTools.stringToBCDNString(subjectDN);
        Iterator iter = certs.iterator();
        while (iter.hasNext()) {
            X509Certificate cacert = (X509Certificate)iter.next();
            if (m_log.isDebugEnabled()) {
                m_log.debug("Comparing the following certificates:\n"
                            + " CA certificate DN: " + cacert.getSubjectDN()
                            + "\n Subject DN: " + dn);
            }
            if (dn.equalsIgnoreCase(CertTools.stringToBCDNString(cacert.getSubjectDN().getName()))) {
                return cacert;
            }
        }
        m_log.info("Did not find matching CA-cert for DN: "+subjectDN);
        return null;
    }

    protected BasicOCSPRespGenerator createOCSPResponse(OCSPReq req, X509Certificate cacert) throws OCSPException {
        if (null == req) {
            throw new IllegalArgumentException();
        }
        BasicOCSPRespGenerator res = new BasicOCSPRespGenerator(cacert.getPublicKey());
		DERObjectIdentifier id_pkix_ocsp_nonce = new DERObjectIdentifier(OCSPObjectIdentifiers.pkix_ocsp + ".2");
        X509Extensions reqexts = req.getRequestExtensions();
        if (reqexts != null) {
            X509Extension ext = (X509Extension)reqexts.getExtension(id_pkix_ocsp_nonce);
            if (null != ext) {
                Hashtable table = new Hashtable();
                Vector vec = null;
                table.put(id_pkix_ocsp_nonce, ext);
                X509Extensions exts = new X509Extensions(table); 
                res.setResponseExtensions(exts);
            }            
        }
        return res;
    }
    
    protected OcspSigningMaterial setupOcspSigningMaterial(X509Certificate cacert) 
    throws CADoesntExistsException, ExtendedCAServiceNotActiveException, IllegalExtendedCAServiceRequestException {
        // Find the OCSP signing key and cert for the issuer
        String issuerdn = CertTools.stringToBCDNString(cacert.getSubjectDN().toString()); 
        int caid = issuerdn.hashCode();
        // Call extended CA services to get our OCSP stuff
        OCSPCAServiceResponse caserviceresp = (OCSPCAServiceResponse)m_signsession.extendedService(m_adm,caid, new OCSPCAServiceRequest(0));
        // Now we can use the returned OCSPServiceResponse to get private key and cetificate chain to sign the ocsp response
        OcspSigningMaterial ret = new OcspSigningMaterial();
        ret.pk = caserviceresp.getOCSPSigningKey();
        Collection coll = caserviceresp.getOCSPSigningCertificateChain();
        m_log.debug("Cert chain for OCSP signing is of size "+coll.size());
        ret.certchain = (X509Certificate[])caserviceresp.getOCSPSigningCertificateChain().toArray(new X509Certificate[coll.size()]);
        return ret;         
    }
    
    public void init(ServletConfig config) 
        throws ServletException {
        super.init(config);
        
        try {
            {
                File cwd = new File(".");
                m_log.debug("OCSPServlet current working directory : '"
                            + cwd.getAbsolutePath()
                            + "'");
            }

            InitialContext ctx = new InitialContext();
            ICertificateStoreSessionLocalHome castorehome = 
                (ICertificateStoreSessionLocalHome) ctx.lookup("java:comp/env/ejb/CertificateStoreSessionLocal");
            m_certStore = castorehome.create();
            ICAAdminSessionLocalHome caadminsessionhome = (ICAAdminSessionLocalHome) ctx.lookup("java:comp/env/ejb/CAAdminSessionLocal");
            m_caadminsession = caadminsessionhome.create();
            m_adm = new Admin(Admin.TYPE_INTERNALUSER);
            ISignSessionLocalHome signhome = (ISignSessionLocalHome) ctx.lookup("java:comp/env/ejb/SignSessionLocal");
            m_signsession = signhome.create();
            
            // Parameters for OCSP signing (private) key
            m_sigAlg = config.getInitParameter("SignatureAlgorithm");
            if (StringUtils.isEmpty(m_sigAlg)) {
                m_log.error("Signature algorithm not defined in initialization parameters.");
                throw new ServletException("Missing signature algorithm in initialization parameters.");
            }
            m_defaultResponderId = config.getInitParameter("defaultResponderID");
            if (StringUtils.isEmpty(m_defaultResponderId)) {
                m_log.error("Default responder id not defined in initialization parameters.");
                throw new ServletException("Missing default responder id in initialization parameters.");
            }
            String initparam = config.getInitParameter("enforceRequestSigning");
            if (m_log.isDebugEnabled()) {
                m_log.debug("Enforce request signing : '" 
                            + (StringUtils.isEmpty(initparam) ? "<not set>" : initparam)
                            + "'");
            }
            m_reqMustBeSigned = true;
            if (!StringUtils.isEmpty(initparam)) {
                if (initparam.equalsIgnoreCase("false") 
                    || initparam.equalsIgnoreCase("no")) {
                    m_reqMustBeSigned = false;
                }
            }
        } catch(Exception e) {
            m_log.error("Unable to initialize OCSPServlet.", e);
            throw new ServletException(e);
        }
    }

    public void doPost(HttpServletRequest request, HttpServletResponse response) 
        throws IOException, ServletException {
        m_log.debug(">doPost()");
        try {
            OCSPResp ocspresp = null;
            BasicOCSPRespGenerator basicRes = null;
            OCSPRespGenerator res = new OCSPRespGenerator();
            OcspSigningMaterial ocspSign = null;
            try {
                X509Extension ext = null;

                OCSPReq req = new OCSPReq(request.getInputStream());
                m_log.debug("OCSpReq: "+new String(Base64.encode(req.getEncoded())));

                loadCertificates();
            
                if (m_log.isDebugEnabled()) {
                    StringBuffer certInfo = new StringBuffer();
                    Iterator iter = m_cacerts.iterator();
                    while (iter.hasNext()) {
                        X509Certificate cert = (X509Certificate)iter.next();
                        certInfo.append(cert.getSubjectDN().getName());
                        certInfo.append(',');
                        certInfo.append(cert.getSerialNumber().toString());
                        certInfo.append('\n');
                    }
                    m_log.debug("Found the following CA certificates : \n" 
                                + certInfo.toString());
                }

            
                /**
                 * check the signature if contained in request.
                 * if the request does not contain a signature
                 * and the servlet is configured in the way 
                 * the a signature is required we send back
                 * 'sigRequired' response.
                 */
                if (m_log.isDebugEnabled()) {
                    m_log.debug("Incoming OCSP request is signed : " + req.isSigned());
                }
                if (m_reqMustBeSigned) {
                    if (!req.isSigned()) {
                        m_log.info("OCSP request unsigned. Servlet enforces signing.");
                        throw new SignRequestException("OCSP request unsigned. Servlet enforces signing.");
                    }                
                    GeneralName requestor = req.getRequestorName();
                    X509Certificate[] certs = req.getCerts("BC");
                    PublicKey pk = null;
                    // We must find a cert to verify the signature with...
                    boolean verifyOK = false;
                    for (int i=0;i<certs.length;i++) {
                        if (req.verify(certs[i].getPublicKey(), "BC") == true) {
                          verifyOK = true;  
                          break;
                        }
                    }
                    if (!verifyOK) {                    
                        m_log.info("Signature of incoming OCSPRequest is invalid.");
                        throw new SignRequestSignatureException("Signature invalid.");
                    }
                }
            
                /**
                 * FIXME: tmeckel
                 * How to implement the list of allowed
                 * OCSP clients which are allowed to talk?
                 * 
                 * check if requestor is allowed to talk
                 * to the CA, if not send back a 'unauthorized'
                 * response
                 */
                //throw new OCSPUnauthorizedException()
                Req[] requests = req.getRequestList();
                if (requests.length <= 0) {
                    String msg = "The OCSP request does not contain any simpleRequest entities.";
                    m_log.error(msg);
                    {
                        // All this just so we can create an error response
                        X509Certificate cacert = findCertificateBySubject(m_defaultResponderId, m_cacerts);
                        ocspSign = setupOcspSigningMaterial(cacert);
                        // Create a basicRes, just to create an error response 
                        basicRes = createOCSPResponse(req, cacert);
                    }
                    throw new MalformedRequestException(msg);
                } else {
                    for (int i=0;i<requests.length;i++) {
                        CertificateID certId = requests[i].getCertID();
                        boolean unknownCA = false; // if the certId was issued by an unknown CA
                        X509Certificate cacert = null;
                        // The algorithm here:
                        // We will sign the response with the CA that issued the first 
                        // certificate(certId) in the request. If the issuing CA is not available
                        // on this server, we sign the response with the default responderId (from params in web.xml).
                        // We have to look up the ca-certificate for each certId in the request though, as we will check
                        // for revocation on the ca-cert as well when checking for revocation on the certId. 
                        try {
                            cacert = findCAByHash(certId, m_cacerts);
                            if (cacert == null) {
                                // We could not find certificate for this request so get certificate for default responder
                                cacert = findCertificateBySubject(m_defaultResponderId, m_cacerts);
                                unknownCA = true;
                            }
                        } catch (OCSPException e) {
                            m_log.error("Unable to generate CA certificate hash.", e);    
                            cacert = null;
                            continue;
                        }
                        // Create a basic response (if we haven't done it already) using the first issuer we find, or the default one
                        if ( (cacert != null) && (basicRes == null) ) {
                            basicRes = createOCSPResponse(req, cacert);
                            ocspSign = setupOcspSigningMaterial(cacert);
                        } else if (cacert == null) {
                            final String msg = "Unable to find CA certificate by issuer name hash: "+Hex.encode(certId.getIssuerNameHash())+", or even the default responder: "+m_defaultResponderId;
                            m_log.error(msg);
                            continue;                    
                        }                            
                        if (unknownCA == true) {
                            final String msg = "Unable to find CA certificate by issuer name hash: "+Hex.encode(certId.getIssuerNameHash())+", using the default reponder to send 'UnknownStatus'";
                            m_log.info(msg);
                            // If we can not find the CA, answer UnknowStatus
                            basicRes.addResponse(certId, new UnknownStatus());
                            continue;                    
                        }                            


                        /*
                         * Implement logic according to
                         * chapter 2.7 in RFC2560
                         * 
                         * 2.7  CA Key Compromise
                         *    If an OCSP responder knows that a particular CA's private key has
                         *    been compromised, it MAY return the revoked state for all
                         *    certificates issued by that CA.
                         */
                        RevokedCertInfo rci;
                        rci = m_certStore.isRevoked(m_adm
                                               , cacert.getIssuerDN().getName()
                                               , cacert.getSerialNumber());
                        if (null != rci && rci.getReason() == RevokedCertInfo.NOT_REVOKED) {
                            rci = null;
                        }
                        if (null == rci) {
                            rci = m_certStore.isRevoked(m_adm
                                                   , cacert.getSubjectDN().getName()
                                                   , certId.getSerialNumber());
                            if (null == rci) {
                                m_log.debug("Unable to find revocation information for certificate with serial '"
                                           + certId.getSerialNumber() + "'"
                                           + " from issuer '" + cacert.getSubjectDN().getName() + "'");
                                basicRes.addResponse(certId, new UnknownStatus());
                            } else {
                                CertificateStatus certStatus = null; // null mean good
                                if (rci.getReason() != RevokedCertInfo.NOT_REVOKED) {
                                    certStatus = new RevokedStatus(new RevokedInfo(
                                        new DERGeneralizedTime(rci.getRevocationDate()), 
                                        /*new CRLReason(rci.getReason())*/null));
                                        // TODO: Temporary workaround for bug in BC
                                } else {
                                    certStatus = null;
                                }
                                if (m_log.isDebugEnabled()) {
                                    m_log.debug("Adding status information for certificate with serial '"
                                               + certId.getSerialNumber() + "'"
                                               + " from issuer '" + cacert.getSubjectDN().getName() + "'");
                                }
                                basicRes.addResponse(certId, certStatus);
                            }
                        } else {
                            CertificateStatus certStatus = new RevokedStatus(new RevokedInfo(
                                new DERGeneralizedTime(rci.getRevocationDate()), 
                                new CRLReason(rci.getReason())));
                            basicRes.addResponse(certId, certStatus);
                        }
                    }
                    if (basicRes != null) {
                        // generate the signed response object
                        BasicOCSPResp basicresp = basicRes.generate(m_sigAlg, ocspSign.getPrivateKey(), ocspSign.getCertChain(), new Date(), "BC" );
                        ocspresp = res.generate(OCSPRespGenerator.SUCCESSFUL, basicresp);                        
                    } else {
                        final String msg = "Unable to find CA certificate and key to generate OCSP response!";
                        m_log.error(msg);
                        throw new ServletException(msg);
                    }
                }
            } catch (MalformedRequestException e) {
                m_log.info("MalformedRequestException caught : ", e);
                // generate the signed response object
                try {                    
                    BasicOCSPResp basicresp = basicRes.generate(m_sigAlg, ocspSign.getPrivateKey(), ocspSign.getCertChain(), new Date(), "BC" );
                    ocspresp = res.generate(OCSPRespGenerator.MALFORMED_REQUEST, basicRes);
                } catch (NoSuchProviderException nspe) {
                    m_log.error("Can't generate any type of OCSP response: ", e);
                    throw new ServletException(e);
                }
                
            } catch (SignRequestException e) {
                m_log.info("SignRequestException caught : ", e);
                // generate the signed response object
                try {                    
                    BasicOCSPResp basicresp = basicRes.generate(m_sigAlg, ocspSign.getPrivateKey(), ocspSign.getCertChain(), new Date(), "BC" );
                    ocspresp = res.generate(OCSPRespGenerator.SIG_REQUIRED, basicRes);
                } catch (NoSuchProviderException nspe) {
                    m_log.error("Can't generate any type of OCSP response: ", e);
                    throw new ServletException(e);
                }
            } catch (Exception e) {
                m_log.error("Unable to handle OCSP request.", e);
                // generate the signed response object
                try {                    
                    BasicOCSPResp basicresp = basicRes.generate(m_sigAlg, ocspSign.getPrivateKey(), ocspSign.getCertChain(), new Date(), "BC" );
                    ocspresp = res.generate(OCSPRespGenerator.INTERNAL_ERROR, basicRes);
                } catch (NoSuchProviderException nspe) {
                    m_log.error("Can't generate any type of OCSP response: ", e);
                    throw new ServletException(e);
                }
            }
            response.getOutputStream().write(ocspresp.getEncoded());
        } catch (OCSPException e) {
            m_log.error("OCSPException caught, fatal error : ", e);
            throw new ServletException(e);
        }        
        m_log.debug("<doPost()");
    } //doPost

    public void doGet(HttpServletRequest request,  HttpServletResponse response) 
        throws IOException, ServletException {
        m_log.debug(">doGet()");
        /**
         * We only support POST operation, so return
         * an appropriate HTTP error code to caller.
         */
        m_log.debug("<doGet()");
    } // doGet

} // OCSPServlet
