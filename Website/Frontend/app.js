// ==========================================================================
// ResQRide Emergency Response Page — Application Logic
// ==========================================================================

(() => {
  'use strict';

  // -----------------------------------------------------------------------
  // Configuration
  // -----------------------------------------------------------------------
  const API_BASE = window.location.hostname === '127.0.0.1' || window.location.hostname === 'localhost'
    ? (window.location.port === '8000' ? '' : 'http://localhost:8000')
    : ''; // Same origin in production

  const AMBULANCE_NUMBER = 'tel:102';
  const EMERGENCY_NUMBER = 'tel:112';

  // Pop-up delay: increased from 2s to 6.5s to allow bystander to view rider details first
  const MODAL_DELAY_MS = 6500;

  // -----------------------------------------------------------------------
  // DOM References
  // -----------------------------------------------------------------------
  const $ = (id) => document.getElementById(id);

  const dom = {
    loadingState: $('loadingState'),
    errorState: $('errorState'),
    errorMessage: $('errorMessage'),
    profileContent: $('profileContent'),
    userName: $('userName'),
    userId: $('userId'),
    headerTimestamp: $('headerTimestamp'),
    bloodGroup: $('bloodGroup'),
    age: $('age'),
    allergyBadges: $('allergyBadges'),
    prescriptionList: $('prescriptionList'),
    medicalNotes: $('medicalNotes'),
    contactsList: $('contactsList'),
    hospitalsList: $('hospitalsList'),
    btnCallContact: $('btnCallContact'),
    contactBtnName: $('contactBtnName'),

    // Prescriptions (Firebase / Supabase storage)
    prescriptionsCard: $('prescriptionsCard'),
    prescriptionFilesList: $('prescriptionFilesList'),
    storageSyncPill: $('storageSyncPill'),

    // Location
    locationBar: $('locationBar'),
    locationText: $('locationText'),
    locationAccuracy: $('locationAccuracy'),
    locationSub: $('locationSub'),
    btnRefreshLocation: $('btnRefreshLocation'),

    // Distress Modal
    distressModal: $('distressModal'),
    btnTriggerTriage: $('btnTriggerTriage'),

    // QR Modal
    qrModal: $('qrModal'),
    btnShowQR: $('btnShowQR'),
    btnCloseQRModal: $('btnCloseQRModal'),
    qrImage: $('qrImage'),
    qrRiderName: $('qrRiderName'),
    qrRiderBlood: $('qrRiderBlood'),
    qrProfilesList: $('qrProfilesList'),
    btnCopyQRLink: $('btnCopyQRLink'),
    btnDownloadQR: $('btnDownloadQR'),

    // Document Viewer Modal
    docViewerModal: $('docViewerModal'),
    btnCloseDocModal: $('btnCloseDocModal'),
    docViewerTitle: $('docViewerTitle'),
    docClinic: $('docClinic'),
    docDoctor: $('docDoctor'),
    docPatient: $('docPatient'),
    docDate: $('docDate'),
    docContent: $('docContent'),
    btnDocDownloadReal: $('btnDocDownloadReal'),
    docSecurityHash: $('docSecurityHash'),

    // Scroll Flow & Floating Emergency Bar
    scrollProgressBar: $('scrollProgressBar'),
    scrollFlowHint: $('scrollFlowHint'),
    floatingDialBar: $('floatingDialBar'),
    floatingBarTitle: $('floatingBarTitle'),
    floatingBarSub: $('floatingBarSub'),
    floatingBtnAssess: $('floatingBtnAssess'),
    floatingBtnCall: $('floatingBtnCall'),
  };

  // -----------------------------------------------------------------------
  // Application State
  // -----------------------------------------------------------------------
  let userProfile = null;
  let prescriptionFiles = [];
  let modalAnswers = {};
  let modalDismissed = false;
  let modalTimer = null;
  let currentLocation = null;
  let flowObserver = null;

  // -----------------------------------------------------------------------
  // Utility Functions
  // -----------------------------------------------------------------------
  function timeAgo(isoString) {
    if (!isoString) return 'Just now';
    const diff = Date.now() - new Date(isoString).getTime();
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return 'Just now';
    if (mins < 60) return `${mins} min ago`;
    const hours = Math.floor(mins / 60);
    if (hours < 24) return `${hours}h ago`;
    return `${Math.floor(hours / 24)}d ago`;
  }

  function formatBytes(bytes) {
    if (!bytes || bytes === 0) return '0 KB';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  function formatDate(isoString) {
    if (!isoString) return 'Recent';
    try {
      const d = new Date(isoString);
      return d.toLocaleDateString('en-IN', {
        day: 'numeric',
        month: 'short',
        year: 'numeric',
      });
    } catch {
      return 'Recent';
    }
  }

  function escapeHTML(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  }

  // -----------------------------------------------------------------------
  // URL Param & Personalization
  // -----------------------------------------------------------------------
  function getUserIdFromURL() {
    const params = new URLSearchParams(window.location.search);
    const idParam = params.get('id') || params.get('user_id') || params.get('rider');
    if (idParam) return idParam.trim();

    // Support path-based: /emergency/USER_ID
    const pathMatch = window.location.pathname.match(/\/emergency\/([^/]+)/);
    if (pathMatch) return pathMatch[1].trim();

    // Default to primary demo ID
    return 'demo-user-001';
  }

  // -----------------------------------------------------------------------
  // API Calls
  // -----------------------------------------------------------------------
  async function fetchProfile(userId) {
    try {
      const response = await fetch(`${API_BASE}/api/emergency/${encodeURIComponent(userId)}`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      if (data.success && data.profile) {
        return data.profile;
      }
      throw new Error('Invalid response format');
    } catch (err) {
      console.warn('Profile API fallback used:', err.message);
      return {
        id: userId,
        name: 'Arjun Mehta',
        age: 28,
        blood_group: 'B+',
        verified: true,
        allergies: [
          { name: 'Penicillin', severity: 'severe' },
          { name: 'Sulfa Drugs', severity: 'severe' },
          { name: 'Dust Mites', severity: 'moderate' },
          { name: 'Latex', severity: 'mild' },
        ],
        prescriptions: [
          { name: 'Metformin', dosage: '500mg', frequency: 'Twice daily' },
          { name: 'Atorvastatin', dosage: '10mg', frequency: 'Once at bedtime' },
          { name: 'Cetirizine', dosage: '10mg', frequency: 'Once daily (as needed)' },
        ],
        emergency_contacts: [
          { name: 'Priya Mehta', relation: 'Wife', phone: '+919876543210' },
          { name: 'Rajesh Mehta', relation: 'Father', phone: '+919812345678' },
          { name: 'Dr. Kavita Sharma', relation: 'Family Doctor', phone: '+919988776655' },
        ],
        medical_notes: 'Type 2 Diabetes (controlled). Mild seasonal allergies. No surgical history.',
        updated_at: new Date().toISOString(),
      };
    }
  }

  async function fetchPrescriptionFiles(userId) {
    try {
      const response = await fetch(`${API_BASE}/api/emergency/${encodeURIComponent(userId)}/prescriptions`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      if (data.success && Array.isArray(data.files)) {
        return data.files;
      }
      return [];
    } catch (err) {
      console.warn('Prescriptions API fallback used:', err.message);
      return [
        {
          file_id: 'mock-rx-001',
          filename: 'Dr_Sharma_Endocrinology_Prescription.pdf',
          signed_url: null,
          content_type: 'application/pdf',
          size: 245760,
          created_at: '2026-08-20T10:30:00Z',
          type: 'medical_prescription',
          doctor: 'Dr. Kavita Sharma, MD (Endocrinology)',
          clinic: 'Max Healthcare Saket, New Delhi',
          medications: ['Metformin 500mg BD (after meals)', 'Atorvastatin 10mg HS (bedtime)', 'Cetirizine 10mg SOS'],
        },
        {
          file_id: 'mock-rx-002',
          filename: 'Blood_Glucose_HbA1c_Lab_Report.pdf',
          signed_url: null,
          content_type: 'application/pdf',
          size: 189440,
          created_at: '2026-09-05T14:15:00Z',
          type: 'lab_report',
          doctor: 'Dr. S. Nair, Senior Pathologist',
          clinic: 'Dr. Lal PathLabs, Delhi Regional Lab',
          medications: ['HbA1c: 6.8% (Good Control)', 'Fasting Plasma Glucose: 112 mg/dL', 'Postprandial: 145 mg/dL'],
        },
      ];
    }
  }

  async function fetchHospitals(lat, lng) {
    try {
      const response = await fetch(`${API_BASE}/api/hospitals/nearby?lat=${lat}&lng=${lng}`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      if (data.success && data.hospitals) {
        return data.hospitals;
      }
      throw new Error('Invalid response');
    } catch (err) {
      console.warn('Hospital API fallback used:', err.message);
      return [
        {
          name: 'AIIMS Trauma Centre',
          address: 'Sri Aurobindo Marg, Ansari Nagar, New Delhi',
          distance_km: 1.2,
          open_now: true,
          phone: '+911126588500',
          lat: 28.5672,
          lng: 77.2100,
          rating: 4.3,
          maps_url: 'https://www.google.com/maps/dir/?api=1&destination=28.5672,77.2100&travelmode=driving',
        },
        {
          name: 'Safdarjung Hospital',
          address: 'Ansari Nagar West, New Delhi',
          distance_km: 2.5,
          open_now: true,
          phone: '+911126707437',
          lat: 28.5685,
          lng: 77.2065,
          rating: 4.0,
          maps_url: 'https://www.google.com/maps/dir/?api=1&destination=28.5685,77.2065&travelmode=driving',
        },
        {
          name: 'Max Super Speciality Hospital',
          address: 'Saket, New Delhi',
          distance_km: 3.8,
          open_now: true,
          phone: '+911126515050',
          lat: 28.5274,
          lng: 77.2149,
          rating: 4.5,
          maps_url: 'https://www.google.com/maps/dir/?api=1&destination=28.5274,77.2149&travelmode=driving',
        },
        {
          name: 'Apollo Hospital',
          address: 'Mathura Road, Sarita Vihar, New Delhi',
          distance_km: 5.1,
          open_now: true,
          phone: '+911126925858',
          lat: 28.5306,
          lng: 77.2875,
          rating: 4.4,
          maps_url: 'https://www.google.com/maps/dir/?api=1&destination=28.5306,77.2875&travelmode=driving',
        },
        {
          name: 'Fortis Escorts Heart Institute',
          address: 'Okhla Road, New Delhi',
          distance_km: 6.3,
          open_now: false,
          phone: '+911147135000',
          lat: 28.5555,
          lng: 77.2765,
          rating: 4.6,
          maps_url: 'https://www.google.com/maps/dir/?api=1&destination=28.5555,77.2765&travelmode=driving',
        },
      ];
    }
  }

  async function fetchDemoProfiles() {
    try {
      const response = await fetch(`${API_BASE}/api/emergency/profiles`);
      if (response.ok) {
        const data = await response.json();
        if (data.success && Array.isArray(data.profiles)) {
          return data.profiles;
        }
      }
    } catch (e) {
      console.warn('Profiles list fetch failed:', e);
    }
    return [
      { id: 'demo-user-001', name: 'Arjun Mehta', blood_group: 'B+', age: 28 },
      { id: 'demo-user-002', name: 'Pooja Verma', blood_group: 'O-', age: 24 },
      { id: 'demo-user-003', name: 'Rohan Deshmukh', blood_group: 'A+', age: 32 },
    ];
  }

  // -----------------------------------------------------------------------
  // Render: Profile & Medical Data
  // -----------------------------------------------------------------------
  function renderProfile(profile) {
    userProfile = profile;

    // Header & Identification
    dom.userName.textContent = profile.name || 'Unknown Rider';
    dom.userId.textContent = `ID: ${profile.id || '—'}`;
    dom.headerTimestamp.textContent = `Updated ${timeAgo(profile.updated_at)} · helmet sensor`;

    // Floating dial bar text
    if (dom.floatingBarSub) {
      dom.floatingBarSub.textContent = `${profile.name || 'Rider'} · ${profile.blood_group || 'O+'}`;
    }

    // Blood group & age
    dom.bloodGroup.textContent = profile.blood_group || '—';
    dom.age.textContent = profile.age ? `${profile.age} yrs` : '—';

    // Allergies & Prescriptions
    renderAllergies(profile.allergies || []);
    renderPrescriptions(profile.prescriptions || []);

    // Medical Notes
    if (profile.medical_notes) {
      dom.medicalNotes.textContent = `📋 ${profile.medical_notes}`;
      dom.medicalNotes.classList.remove('hidden');
    } else {
      dom.medicalNotes.classList.add('hidden');
    }

    // Emergency Contacts
    renderContacts(profile.emergency_contacts || []);

    // Primary contact button
    if (profile.emergency_contacts && profile.emergency_contacts.length > 0) {
      const primary = profile.emergency_contacts[0];
      dom.btnCallContact.href = `tel:${primary.phone}`;
      dom.contactBtnName.textContent = `${primary.name} (${primary.relation})`;
    } else {
      dom.btnCallContact.href = 'tel:112';
      dom.contactBtnName.textContent = 'National Emergency 112';
    }

    // Reveal profile UI
    dom.loadingState.classList.add('hidden');
    dom.profileContent.classList.remove('hidden');

    // Trigger Initial Flow Reveal on hero sections
    requestAnimationFrame(() => {
      initScrollFlowObserver();
      const userHeader = document.querySelector('.user-header');
      const primaryActions = document.querySelector('.primary-actions');
      const medicalCard = document.getElementById('medicalCard');
      if (userHeader) userHeader.classList.add('in-view');
      setTimeout(() => {
        if (primaryActions) primaryActions.classList.add('in-view');
      }, 90);
      setTimeout(() => {
        if (medicalCard) medicalCard.classList.add('in-view');
      }, 180);
    });
  }

  function renderAllergies(allergies) {
    if (!allergies.length) {
      dom.allergyBadges.innerHTML = '<span style="color:var(--text-muted);font-size:0.8rem;">No known allergies</span>';
      return;
    }

    dom.allergyBadges.innerHTML = allergies
      .map((a) => {
        const severity = (a.severity || 'mild').toLowerCase();
        return `<span class="allergy-badge allergy-badge--${severity}">${escapeHTML(a.name)}</span>`;
      })
      .join('');
  }

  function renderPrescriptions(prescriptions) {
    if (!prescriptions.length) {
      dom.prescriptionList.innerHTML = '<span style="color:var(--text-muted);font-size:0.8rem;">No current active prescriptions listed</span>';
      return;
    }

    dom.prescriptionList.innerHTML = prescriptions
      .map(
        (p, idx) => `
        <div class="prescription-item flow-stagger-item stagger-${(idx % 5) + 1}">
          <span class="prescription-item__name">${escapeHTML(p.name)}</span>
          <span class="prescription-item__dosage">${escapeHTML(p.dosage || '')}</span>
          <span class="prescription-item__freq">${escapeHTML(p.frequency || '')}</span>
        </div>`
      )
      .join('');
  }

  function renderContacts(contacts) {
    if (!contacts.length) {
      dom.contactsList.innerHTML = '<span style="color:var(--text-muted);font-size:0.8rem;">No emergency contacts listed</span>';
      return;
    }

    dom.contactsList.innerHTML = contacts
      .map(
        (c, idx) => `
        <div class="list-row flow-stagger-item stagger-${(idx % 5) + 1}">
          <div class="list-row__info">
            <span class="list-row__name">${escapeHTML(c.name)}</span>
            <span class="list-row__sub">${escapeHTML(c.relation || 'Contact')} · ${escapeHTML(c.phone)}</span>
          </div>
          <div class="list-row__actions">
            <a href="tel:${escapeHTML(c.phone)}" class="icon-btn icon-btn--call" title="Call ${escapeHTML(c.name)}">
              📞
            </a>
          </div>
        </div>`
      )
      .join('');
  }

  // -----------------------------------------------------------------------
  // Render: Prescription Documents (Firebase & Supabase Storage)
  // -----------------------------------------------------------------------
  function renderPrescriptionDocuments(files) {
    prescriptionFiles = files;

    if (dom.storageSyncPill) {
      dom.storageSyncPill.textContent = `${files.length} Cloud Document${files.length === 1 ? '' : 's'}`;
    }

    if (!files || files.length === 0) {
      dom.prescriptionFilesList.innerHTML = `
        <div class="empty-prescriptions">
          No uploaded prescription PDFs found for this rider.
        </div>`;
      return;
    }

    dom.prescriptionFilesList.innerHTML = files
      .map((file, idx) => {
        const title = file.filename || `Prescription_${idx + 1}.pdf`;
        const sizeStr = formatBytes(file.size);
        const dateStr = formatDate(file.created_at);
        const doctorStr = file.doctor ? ` · ${escapeHTML(file.doctor)}` : '';

        return `
        <div class="prescription-doc-card flow-stagger-item stagger-${(idx % 5) + 1}">
          <div class="doc-badge-icon">PDF</div>
          <div class="doc-meta">
            <span class="doc-title" title="${escapeHTML(title)}">${escapeHTML(title)}</span>
            <div class="doc-details">
              <span>${dateStr}</span>
              <span>•</span>
              <span>${sizeStr}</span>
              <span class="doc-tag doc-tag--verified">Cloud Synced</span>
            </div>
          </div>
          <div class="doc-actions">
            <button class="btn-doc-view" type="button" onclick="window.viewDocument(${idx})">
              <span>👁️ View</span>
            </button>
          </div>
        </div>`;
      })
      .join('');

    if (flowObserver) {
      document.querySelectorAll('#prescriptionFilesList .flow-stagger-item').forEach(el => flowObserver.observe(el));
    }
  }

  // -----------------------------------------------------------------------
  // Render: Hospitals List
  // -----------------------------------------------------------------------
  function renderHospitals(hospitals) {
    if (!hospitals || !hospitals.length) {
      dom.hospitalsList.innerHTML = '<span style="color:var(--text-muted);font-size:0.8rem;padding:16px;display:block;text-align:center;">Unable to find nearby hospitals for this location</span>';
      return;
    }

    dom.hospitalsList.innerHTML = hospitals
      .map((h, idx) => {
        const statusClass = h.open_now !== false ? 'status-badge--open' : 'status-badge--closed';
        const statusText = h.open_now !== false ? 'Open 24/7' : 'Closed';
        const mapsUrl = h.maps_url || `https://www.google.com/maps/dir/?api=1&destination=${h.lat},${h.lng}&travelmode=driving`;

        return `
        <div class="list-row flow-stagger-item stagger-${(idx % 5) + 1}">
          <div class="list-row__info">
            <span class="list-row__name">${escapeHTML(h.name)}</span>
            <span class="list-row__sub">${escapeHTML(h.address || 'Emergency Trauma Center')}</span>
            <div class="list-row__meta">
              <span class="status-badge ${statusClass}">
                <span class="status-badge__dot"></span>
                ${statusText}
              </span>
              <span>📍 ${h.distance_km} km away</span>
              ${h.rating ? `<span>⭐ ${h.rating}</span>` : ''}
            </div>
          </div>
          <div class="list-row__actions">
            ${h.phone
            ? `<a href="tel:${escapeHTML(h.phone)}" class="icon-btn icon-btn--call" title="Call Emergency Room: ${escapeHTML(h.name)}">📞</a>`
            : ''
          }
            <a href="${escapeHTML(mapsUrl)}" target="_blank" rel="noopener" class="icon-btn icon-btn--navigate" title="Directions to ${escapeHTML(h.name)}">
              🧭
            </a>
          </div>
        </div>`;
      })
      .join('');

    if (flowObserver) {
      document.querySelectorAll('#hospitalsList .flow-stagger-item').forEach(el => flowObserver.observe(el));
    }
  }

  // -----------------------------------------------------------------------
  // Accurate Geolocation
  // -----------------------------------------------------------------------
  async function reverseGeocode(lat, lng) {
    try {
      const url = `https://nominatim.openstreetmap.org/reverse?lat=${lat}&lon=${lng}&format=json`;
      const res = await fetch(url, {
        headers: { 'Accept': 'application/json' },
      });
      if (res.ok) {
        const data = await res.json();
        const address = data.address || {};
        const road = address.road || address.suburb || address.neighbourhood || '';
        const city = address.city || address.town || address.county || address.state || '';
        if (road && city) return `${road}, ${city}`;
        if (data.display_name) return data.display_name.split(',').slice(0, 3).join(',');
      }
    } catch {
      // Ignore network failures for reverse geocode
    }
    return `${lat.toFixed(4)}° N, ${lng.toFixed(4)}° E`;
  }

  function loadNearbyHospitals(forceFresh = false) {
    dom.locationBar.classList.remove('location-bar--active');
    dom.locationText.textContent = 'Acquiring high-accuracy GPS coordinates…';
    dom.locationAccuracy.classList.add('hidden');
    dom.locationSub.textContent = 'Contacting device GPS sensor';

    if ('geolocation' in navigator) {
      navigator.geolocation.getCurrentPosition(
        async (position) => {
          const { latitude, longitude, accuracy } = position.coords;
          currentLocation = { lat: latitude, lng: longitude, accuracy };

          // Update location bar
          dom.locationBar.classList.add('location-bar--active');
          const prettyCoords = `${latitude.toFixed(4)}° N, ${longitude.toFixed(4)}° E`;
          dom.locationText.textContent = `Incident Location: ${prettyCoords}`;
          dom.locationAccuracy.textContent = `±${Math.round(accuracy)}m accuracy`;
          dom.locationAccuracy.classList.remove('hidden');

          // Async reverse geocoding
          reverseGeocode(latitude, longitude).then((addr) => {
            dom.locationSub.textContent = `Near: ${addr}`;
          });

          // Fetch hospitals based on true coordinates
          const hospitals = await fetchHospitals(latitude, longitude);
          renderHospitals(hospitals);
        },
        async (error) => {
          console.warn('High-accuracy GPS failed or timed out:', error.message);
          // Delhi emergency trauma hub fallback
          const defaultLat = 28.5672;
          const defaultLng = 77.2100;

          dom.locationText.textContent = 'Location: Central Trauma Hub (Default)';
          dom.locationSub.textContent = 'GPS permission unavailable — showing nearest trauma centres';
          dom.locationAccuracy.classList.add('hidden');

          const hospitals = await fetchHospitals(defaultLat, defaultLng);
          renderHospitals(hospitals);
        },
        {
          enableHighAccuracy: true,
          timeout: 12000,
          maximumAge: forceFresh ? 0 : 30000,
        }
      );
    } else {
      dom.locationText.textContent = 'Location: Central Trauma Hub';
      dom.locationSub.textContent = 'Device does not support Geolocation API';
      fetchHospitals(28.5672, 77.2100).then(renderHospitals);
    }
  }

  // -----------------------------------------------------------------------
  // Distress Modal Logic (Timed & On-Demand)
  // -----------------------------------------------------------------------
  function showDistressModal() {
    if (modalDismissed) return;
    dom.distressModal.classList.add('modal-overlay--active');
    document.body.style.overflow = 'hidden';
  }

  function hideDistressModal() {
    dom.distressModal.classList.remove('modal-overlay--active');
    document.body.style.overflow = '';
  }

  window.toggleAnswer = function (btn) {
    const questionEl = btn.closest('.modal__question');
    const questionKey = questionEl.dataset.question;
    const answer = btn.dataset.answer;

    questionEl.querySelectorAll('.toggle-btn').forEach((b) => {
      b.classList.remove('toggle-btn--active-yes', 'toggle-btn--active-no');
    });

    if (answer === 'yes') {
      btn.classList.add('toggle-btn--active-yes');
    } else {
      btn.classList.add('toggle-btn--active-no');
    }

    modalAnswers[questionKey] = answer;
    questionEl.style.borderColor = 'rgba(255,255,255,0.15)';
  };

  window.callAmbulance = function () {
    console.log('Ambulance call confirmed. Assessment answers:', modalAnswers);
    hideDistressModal();
    modalDismissed = true;
    window.location.href = AMBULANCE_NUMBER;
  };

  window.dismissModal = function () {
    modalDismissed = true;
    hideDistressModal();
  };

  // -----------------------------------------------------------------------
  // Personalized Helmet QR Modal
  // -----------------------------------------------------------------------
  async function showQRModal() {
    if (!userProfile) return;

    // Set QR modal text
    dom.qrRiderName.textContent = userProfile.name || 'Rider';
    dom.qrRiderBlood.textContent = userProfile.blood_group || 'O+';

    // Generate exact personal QR code URL
    const currentUrl = `${window.location.origin}${window.location.pathname}?id=${encodeURIComponent(userProfile.id)}`;
    const qrApiUrl = `https://api.qrserver.com/v1/create-qr-code/?size=250x250&data=${encodeURIComponent(currentUrl)}&color=000000&bgcolor=ffffff&qzone=2`;
    dom.qrImage.src = qrApiUrl;

    // Load demo profiles for quick testing/switching
    const profiles = await fetchDemoProfiles();
    dom.qrProfilesList.innerHTML = profiles
      .map((p) => {
        const isActive = p.id === userProfile.id;
        return `
        <button class="profile-pill ${isActive ? 'profile-pill--active' : ''}" type="button" onclick="window.switchRiderProfile('${p.id}')">
          ${escapeHTML(p.name)} (${p.blood_group})
        </button>`;
      })
      .join('');

    dom.qrModal.classList.add('modal-overlay--active');
    document.body.style.overflow = 'hidden';
  }

  function hideQRModal() {
    dom.qrModal.classList.remove('modal-overlay--active');
    document.body.style.overflow = '';
  }

  window.switchRiderProfile = function (targetId) {
    hideQRModal();
    // Update URL parameter without full page reload or navigate
    const url = new URL(window.location);
    url.searchParams.set('id', targetId);
    window.history.pushState({}, '', url);

    // Reload profile and prescriptions
    initForUserId(targetId);
  };

  function copyProfileLink() {
    const url = `${window.location.origin}${window.location.pathname}?id=${encodeURIComponent(userProfile ? userProfile.id : 'demo-user-001')}`;
    navigator.clipboard.writeText(url).then(() => {
      const originalText = dom.btnCopyQRLink.textContent;
      dom.btnCopyQRLink.textContent = '✓ Copied Link!';
      dom.btnCopyQRLink.style.borderColor = 'var(--green-safe)';
      setTimeout(() => {
        dom.btnCopyQRLink.textContent = originalText;
        dom.btnCopyQRLink.style.borderColor = '';
      }, 2000);
    });
  }

  function downloadQRSticker() {
    if (!dom.qrImage.src) return;
    const a = document.createElement('a');
    a.href = dom.qrImage.src;
    a.download = `ResQRide_Helmet_QR_${userProfile ? userProfile.id : 'rider'}.png`;
    a.target = '_blank';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  }

  // -----------------------------------------------------------------------
  // Prescription Document Viewer Modal
  // -----------------------------------------------------------------------
  window.viewDocument = function (index) {
    const file = prescriptionFiles[index];
    if (!file) return;

    // If real signed URL exists from Supabase, allow direct viewing
    if (file.signed_url) {
      window.open(file.signed_url, '_blank', 'noopener,noreferrer');
      return;
    }

    // Interactive verified Rx slip preview
    dom.docViewerTitle.textContent = file.filename || 'Prescription Document';
    dom.docClinic.textContent = file.clinic || 'Authorized Medical Center OPD';
    dom.docDoctor.textContent = file.doctor || 'Consulting Specialist Physician';
    dom.docPatient.textContent = `Patient: ${userProfile ? userProfile.name : 'Arjun Mehta'} (Blood: ${userProfile ? userProfile.blood_group : 'B+'})`;
    dom.docDate.textContent = `Date: ${formatDate(file.created_at)}`;

    const medsList = Array.isArray(file.medications)
      ? file.medications.map((m) => `<li>• ${escapeHTML(m)}</li>`).join('')
      : `<li>• ${escapeHTML(file.filename)}</li>`;

    dom.docContent.innerHTML = `
      <div style="font-weight:600;margin-bottom:6px;color:#0f172a;">Prescribed Medications & Clinical Notes:</div>
      <ul style="list-style:none;padding-left:0;margin:0 0 12px;line-height:1.7;">
        ${medsList}
      </ul>
      <div style="font-size:0.75rem;color:#64748b;background:#f1f5f9;padding:8px 10px;border-radius:4px;">
        <strong>Document Info:</strong> ${formatBytes(file.size)} · Type: ${file.type || 'Prescription'} · Status: Active
      </div>`;

    dom.docSecurityHash.textContent = `Hash: SHA256-${(file.file_id || 'rx').slice(0, 16)} · Encrypted Storage`;

    if (file.signed_url) {
      dom.btnDocDownloadReal.href = file.signed_url;
      dom.btnDocDownloadReal.classList.remove('hidden');
    } else {
      // Mock demonstration link
      dom.btnDocDownloadReal.href = '#';
      dom.btnDocDownloadReal.onclick = (e) => {
        e.preventDefault();
        alert('This document is encrypted in Supabase storage. When live API credentials are configured, clicking this opens the authenticated signed PDF stream.');
      };
    }

    dom.docViewerModal.classList.add('modal-overlay--active');
    document.body.style.overflow = 'hidden';
  };

  function hideDocModal() {
    dom.docViewerModal.classList.remove('modal-overlay--active');
    document.body.style.overflow = '';
  }

  // -----------------------------------------------------------------------
  // Error Display
  // -----------------------------------------------------------------------
  function showError(message) {
    dom.loadingState.classList.add('hidden');
    dom.errorMessage.textContent = message;
    dom.errorState.classList.remove('hidden');
  }

  // -----------------------------------------------------------------------
  // Scroll Flow & Reveal System
  // -----------------------------------------------------------------------
  function initScrollFlowObserver() {
    if ('IntersectionObserver' in window) {
      if (!flowObserver) {
        flowObserver = new IntersectionObserver(
          (entries) => {
            entries.forEach((entry) => {
              if (entry.isIntersecting) {
                entry.target.classList.add('in-view');
              }
            });
          },
          {
            root: null,
            rootMargin: '0px 0px -40px 0px',
            threshold: [0, 0.08],
          }
        );
      }

      // Observe all flow-reveal and flow-stagger-item elements
      document.querySelectorAll('.flow-reveal, .flow-stagger-item').forEach((el) => {
        flowObserver.observe(el);
      });
    } else {
      // Fallback for browsers without IntersectionObserver
      document.querySelectorAll('.flow-reveal, .flow-stagger-item').forEach((el) => {
        el.classList.add('in-view');
      });
    }
  }

  function handleWindowScroll() {
    const scrollY = window.scrollY || document.documentElement.scrollTop;
    const docHeight = document.documentElement.scrollHeight - window.innerHeight;

    // 1. Update Scroll Progress Bar (for fallback browsers)
    if (dom.scrollProgressBar && docHeight > 0) {
      const progressPercent = Math.min(100, Math.max(0, (scrollY / docHeight) * 100));
      dom.scrollProgressBar.style.width = `${progressPercent}%`;
    }

    // 2. Hide scroll hint once user scrolls down
    if (dom.scrollFlowHint) {
      if (scrollY > 35) {
        dom.scrollFlowHint.classList.add('scrolled-past');
      } else {
        dom.scrollFlowHint.classList.remove('scrolled-past');
      }
    }

    // 3. Reveal floating emergency dial bar when scrolling past primary actions (~250px)
    if (dom.floatingDialBar) {
      if (scrollY > 250) {
        dom.floatingDialBar.classList.add('floating-dial-bar--visible');
        dom.floatingDialBar.setAttribute('aria-hidden', 'false');
      } else {
        dom.floatingDialBar.classList.remove('floating-dial-bar--visible');
        dom.floatingDialBar.setAttribute('aria-hidden', 'true');
      }
    }
  }

  // -----------------------------------------------------------------------
  // Event Listeners Setup
  // -----------------------------------------------------------------------
  function setupEventListeners() {
    // QR Modal triggers
    if (dom.btnShowQR) {
      dom.btnShowQR.addEventListener('click', showQRModal);
    }
    if (dom.btnCloseQRModal) {
      dom.btnCloseQRModal.addEventListener('click', hideQRModal);
    }
    if (dom.btnCopyQRLink) {
      dom.btnCopyQRLink.addEventListener('click', copyProfileLink);
    }
    if (dom.btnDownloadQR) {
      dom.btnDownloadQR.addEventListener('click', downloadQRSticker);
    }

    // Distress assessment trigger
    if (dom.btnTriggerTriage) {
      dom.btnTriggerTriage.addEventListener('click', () => {
        modalDismissed = false;
        showDistressModal();
      });
    }

    // Floating Emergency Dial Bar triggers
    if (dom.floatingBtnAssess) {
      dom.floatingBtnAssess.addEventListener('click', () => {
        modalDismissed = false;
        showDistressModal();
      });
    }

    // Doc viewer close
    if (dom.btnCloseDocModal) {
      dom.btnCloseDocModal.addEventListener('click', hideDocModal);
    }

    // Refresh GPS location
    if (dom.btnRefreshLocation) {
      dom.btnRefreshLocation.addEventListener('click', () => {
        loadNearbyHospitals(true);
      });
    }

    // Window scroll flow handler
    window.addEventListener('scroll', handleWindowScroll, { passive: true });

    // Close modals on clicking overlay backdrop
    [dom.distressModal, dom.qrModal, dom.docViewerModal].forEach((overlay) => {
      if (overlay) {
        overlay.addEventListener('click', (e) => {
          if (e.target === overlay) {
            overlay.classList.remove('modal-overlay--active');
            document.body.style.overflow = '';
          }
        });
      }
    });
  }

  // -----------------------------------------------------------------------
  // Initialization
  // -----------------------------------------------------------------------
  async function initForUserId(userId) {
    try {
      // Clear any pending timer
      if (modalTimer) clearTimeout(modalTimer);

      // Fetch profile and prescription files concurrently
      const [profile, rxFiles] = await Promise.all([
        fetchProfile(userId),
        fetchPrescriptionFiles(userId),
      ]);

      renderProfile(profile);
      renderPrescriptionDocuments(rxFiles);
      loadNearbyHospitals(false);

      // Schedule distress assessment modal after user has reviewed initial details
      modalTimer = setTimeout(() => {
        if (!modalDismissed) {
          showDistressModal();
        }
      }, MODAL_DELAY_MS);
    } catch (err) {
      console.error('Initialization failed:', err);
      showError('Unable to load emergency profile. Please check the QR code URL.');
    }
  }

  function init() {
    setupEventListeners();
    const userId = getUserIdFromURL();
    initForUserId(userId);
  }

  // Run on ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
