package com.example.mediatalk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Timer;
import java.util.TimerTask;

import android.media.MediaRecorder;
import android.media.MediaRecorder.OnErrorListener;
import android.media.MediaRecorder.OnInfoListener;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;

import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;
import android.widget.VideoView;

@SuppressLint("NewApi")
public class MainActivity extends Activity implements Callback {
	Button buttonStart, buttonStop;
	Camera camera;
	SurfaceView surface;
	SurfaceHolder holder;
	MediaRecorder recorder;
	LocalSocket sender, reciver;
	LocalServerSocket cameraService;
	Thread sendThread;
	VideoView video;
	private VideoView video2;
	private int intCapturedFrameCount = 0;
	private int offsetInRTP_pocket_buffer = 0;

	private int srcPort = 8888, destPort = 8888;
	private static final byte RTP_POCKET_SSRC[] = { (byte) 0xaf, (byte) 0x3f,
			(byte) 0x88, (byte) 0x88 };
	private short RTP_sn = 0x1111;
	private int RTP_timestamp = 0x0;
	private static final int FRAME_RATE = 15;
	private static final int RTP_INCREACEMENT = 90000 / FRAME_RATE; // 90KHz is
																	// defined
																	// in
																	// RFC2190

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.setContentView(R.layout.activity_main);

		setEnviorment();
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
		// TODO Auto-generated method stub
		if (camera != null) {
			try {
				camera.setPreviewDisplay(holder);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		// TODO Auto-generated method stub
		if (camera != null) {
			try {
				camera.setPreviewDisplay(holder);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		// TODO Auto-generated method stub
		stopRecord();
	}

	private void startCamera() {
		camera = Camera.open();

		try {
			camera.setPreviewDisplay(holder);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		camera.startPreview();
	}

	private void setEnviorment() {
		// Set field
		buttonStart = (Button) this.findViewById(R.id.start);
		buttonStop = (Button) this.findViewById(R.id.stop);
		surface = (SurfaceView) this.findViewById(R.id.surfaceview);
		video = (VideoView) this.findViewById(R.id.video);

		reciver = new LocalSocket();
		try {
			cameraService = new LocalServerSocket("VideoCamera");
			reciver.connect(new LocalSocketAddress("VideoCamera"));
			reciver.setReceiveBufferSize(500000);
			reciver.setSendBufferSize(500000);
			sender = cameraService.accept();
			sender.setReceiveBufferSize(500000);
			sender.setSendBufferSize(500000);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		holder = surface.getHolder();
		holder.addCallback(this);
		holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		// Set event
		buttonStart.setOnClickListener(new StartClickListener());
		buttonStop.setOnClickListener(new StopClickListener());

		try {
			InetAddress a = InetAddress.getByName("sc-host");
			Toast.makeText(this, a.toString(), Toast.LENGTH_SHORT).show();
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void stopPreview() {
		if (camera != null) {
			camera.stopPreview();
			camera.release();

			camera = null;
		}
	}

	private void startRecord() {
		String saveFile = null;
		try {
			saveFile = File.createTempFile("Video", ".mpg").getAbsolutePath();
			Toast.makeText(this, saveFile, Toast.LENGTH_LONG).show();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			Toast.makeText(this, "Fail,Cann't create file on your device.",
					Toast.LENGTH_SHORT).show();
			return;
		}

		recorder = new MediaRecorder();
		recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
		recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
		recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H263);
		recorder.setVideoSize(176, 144);
		recorder.setVideoFrameRate(15);
		recorder.setPreviewDisplay(holder.getSurface());
		// recorder.setOutputFile(saveFile);
		recorder.setOutputFile(sender.getFileDescriptor());

		try {
			recorder.prepare();
		} catch (IllegalStateException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		recorder.start();
		// startSend();
		startVideo();
	}

	RtpSocket rtpSocket = null;

	private void startVideo() {
		try {
			rtpSocket = new RtpSocket(new DatagramSocket(srcPort),
					InetAddress.getByName("sc-host"), srcPort);
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		(sendThread = new Thread() {
			public void run() {
				int frame_size = 1400;
				byte[] buffer = new byte[frame_size + 14];
				buffer[12] = 4;
				RtpPacket rtp_packet = new RtpPacket(buffer, 0);
				int seqn = 0;
				int num, number = 0, src, dest, len = 0, head = 0, lasthead = 0, lasthead2 = 0, cnt = 0, stable = 0;
				long now, lasttime = 0;
				double avgrate = 45000;
				double avglen = avgrate / 20;

				InputStream fis = null;
				try {
					fis = reciver.getInputStream();
				} catch (IOException e1) {
					// if (!Sipdroid.release) e1.printStackTrace();
					// rtp_socket.getDatagramSocket().close();
					return;
				}

				rtp_packet.setPayloadType(103);
				while (reciver != null) {
					num = -1;
					try {
						num = fis
								.read(buffer, 14 + number, frame_size - number);
					} catch (IOException e) {
						break;
					}
					if (num < 0) {
						try {
							sleep(20);
						} catch (InterruptedException e) {
							break;
						}
						continue;
					}
					number += num;
					head += num;
					try {
						now = SystemClock.elapsedRealtime();
						if (lasthead != head + fis.available() && ++stable >= 5
								&& now - lasttime > 700) {
							if (cnt != 0 && len != 0)
								avglen = len / cnt;
							if (lasttime != 0) {
								// fps = (int)((double)cnt*1000/(now-lasttime));
								avgrate = (double) ((head + fis.available()) - lasthead2)
										* 1000 / (now - lasttime);
							}
							lasttime = now;
							lasthead = head + fis.available();
							lasthead2 = head;
							len = cnt = stable = 0;
						}
					} catch (IOException e1) {
						break;
					}

					for (num = 14; num <= 14 + number - 2; num++)
						if (buffer[num] == 0 && buffer[num + 1] == 0)
							break;
					if (num > 14 + number - 2) {
						num = 0;
						rtp_packet.setMarker(false);
					} else {
						num = 14 + number - num;
						rtp_packet.setMarker(true);
					}

					rtp_packet.setSequenceNumber(seqn++);
					rtp_packet.setPayloadLength(number - num + 2);
					if (seqn > 10)
						try {
							rtpSocket.send(rtp_packet);
							len += number - num;
						} catch (IOException e) {
							// if (!Sipdroid.release) e.printStackTrace();
							break;
						}

					if (num > 0) {
						num -= 2;
						dest = 14;
						src = 14 + number - num;
						if (num > 0 && buffer[src] == 0) {
							src++;
							num--;
						}
						number = num;
						while (num-- > 0)
							buffer[dest++] = buffer[src++];
						buffer[12] = 4;

						cnt++;
						try {
							if (avgrate != 0)
								Thread.sleep((int) (avglen / avgrate * 1000));
						} catch (Exception e) {
							break;
						}
						rtp_packet
								.setTimestamp(SystemClock.elapsedRealtime() * 90);
					} else {
						number = 0;
						buffer[12] = 0;
					}
					// if (change) {
					// change = false;
					// long time = SystemClock.elapsedRealtime();
					//
					// try {
					// while (fis.read(buffer,14,frame_size) > 0 &&
					// SystemClock.elapsedRealtime()-time < 3000);
					// } catch (Exception e) {
					// }
					// number = 0;
					// buffer[12] = 0;
					// }
				}

				try {
					while (fis.read(buffer, 0, frame_size) > 0) {

					}
				} catch (IOException e) {
				}
			}
		}).start();
	}

	private void startSend() {

		(sendThread = new Thread() {
			// start a new thread for capturing
			public void run() {
				final int READ_SIZE = 1024; // bytes count for every reading
											// from 'inputStreamForReceive'
				final int BUFFER_SIZE_RECEIVE = READ_SIZE * 128;
				final int BUFFER_SIZE_RTP = READ_SIZE * 20;
				boolean foundFrameHead = false;
				int i;
				byte[] PSC_checkArr = new byte[] { (byte) 0xFF, (byte) 0xFF,
						(byte) 0xFF };
				byte[] RTP_pocket_buffer = new byte[BUFFER_SIZE_RTP];
				byte[] bufferForReceive = new byte[BUFFER_SIZE_RECEIVE]; // 64K
				int num = 0;
				InputStream inputStreamForReceive = null;

				// udp
				final int RTP_PORT_SENDING = 8000;
				final int RTP_PORT_DEST = 8000;
				DatagramSocket socketSendRPT = null;
				DatagramPacket RtpPocket = null;
				InetAddress PC_IpAddress = null;
				try {
					PC_IpAddress = InetAddress.getByName("sc-host");
					Log.d("----------------", PC_IpAddress.toString());

				} catch (UnknownHostException e) {
					e.printStackTrace();
				}

				try {
					socketSendRPT = new DatagramSocket(RTP_PORT_SENDING);
				} catch (SocketException e) {
					e.printStackTrace();
				}

				// get the data stream from mediaRecorder
				try {
					inputStreamForReceive = reciver.getInputStream();
				} catch (IOException e1) {
					return;
				}

				while (recorder != null) {
					// 1. read data from receive stream until to the end
					int offsetInBufferForReceive = 0;
					do {
						try {
							num = inputStreamForReceive.read(bufferForReceive,
									offsetInBufferForReceive, READ_SIZE);
							if (num < 0) { // there's nothing in there
								// wait for a while
								try {
									Thread.currentThread().sleep(5);
								} catch (InterruptedException e1) {
									e1.printStackTrace();
								}
								break;
							}

							offsetInBufferForReceive += num; // offsetInBufferForReceive
																// points to
																// next position
																// can be wrote
							if (num < READ_SIZE
									|| offsetInBufferForReceive == BUFFER_SIZE_RECEIVE) { // indicating
																							// the
																							// end
																							// of
																							// this
																							// reading
								break; // or bufferForReceive is full
							}
						} catch (IOException e) {
							break;
						}
					} while (false);// do

					// 2. find Picture Start Code (PSC) (22 bits) in
					// bufferForReceive
					for (i = 0; i < offsetInBufferForReceive; i++) {
						PSC_checkArr[0] = PSC_checkArr[1];
						PSC_checkArr[1] = PSC_checkArr[2];
						PSC_checkArr[2] = bufferForReceive[i];

						// see if got the PSC
						if (PSC_checkArr[0] == 0
								&& PSC_checkArr[1] == 0
								&& (PSC_checkArr[2] & (byte) 0xFC) == (byte) 0x80) {
							// found the PSC
							if (foundFrameHead == false) {
								foundFrameHead = true;
								// copy current byte to packet buffer
								intializeRTP_PocketBuffer(RTP_pocket_buffer);
								RTP_pocket_buffer[offsetInRTP_pocket_buffer] = bufferForReceive[i];
								offsetInRTP_pocket_buffer++;
							} else {
								// delete two zeros in the end of the buffer
								offsetInRTP_pocket_buffer -= 2;

								// TODO:3. send the packet, and reset the buffer
								RtpPocket = new DatagramPacket(
										RTP_pocket_buffer,
										offsetInRTP_pocket_buffer,
										PC_IpAddress, RTP_PORT_DEST);
								try {
									socketSendRPT.send(RtpPocket);
								} catch (IOException e) {
									e.printStackTrace();
								}

								intCapturedFrameCount++; // for statistics
								// copy current byte to packet buffer
								intializeRTP_PocketBuffer(RTP_pocket_buffer);
								RTP_pocket_buffer[offsetInRTP_pocket_buffer] = bufferForReceive[i];
								offsetInRTP_pocket_buffer++;
							}

						} else// if NOT got the PSC
						{
							if (foundFrameHead == false) {
								continue;
							} else {
								// copy current byte to packet buffer
								RTP_pocket_buffer[offsetInRTP_pocket_buffer] = bufferForReceive[i];
								offsetInRTP_pocket_buffer++;
							}
						}
						if (offsetInRTP_pocket_buffer >= BUFFER_SIZE_RTP) {
							foundFrameHead = false;
							offsetInRTP_pocket_buffer = 0;
						}

					}
				}

			}
		}).start();// start new thread
	}

	// ****************************************
	// Initialize RTP pocket Buffer
	// ****************************************
	private void intializeRTP_PocketBuffer(byte[] bufferRTP) {

		bufferRTP[0] = (byte) 0x80;
		bufferRTP[1] = (byte) 0xA2;

		// sn
		bufferRTP[2] = (byte) (RTP_sn >> 8);
		bufferRTP[3] = (byte) RTP_sn;
		RTP_sn++;

		// timestamp
		bufferRTP[4] = (byte) (RTP_timestamp >> 24);
		bufferRTP[5] = (byte) (RTP_timestamp >> 16);
		bufferRTP[6] = (byte) (RTP_timestamp >> 8);
		bufferRTP[7] = (byte) RTP_timestamp;
		RTP_timestamp += RTP_INCREACEMENT;

		// ssrc
		bufferRTP[8] = RTP_POCKET_SSRC[0];
		bufferRTP[9] = RTP_POCKET_SSRC[1];
		bufferRTP[10] = RTP_POCKET_SSRC[2];
		bufferRTP[11] = RTP_POCKET_SSRC[3];

		// rtp's beginning
		bufferRTP[12] = 0;
		bufferRTP[13] = 0;

		offsetInRTP_pocket_buffer = 14;

	}

	private void stopRecord() {
		if (recorder != null) {
			recorder.stop();
			recorder.release();
		}

		if (camera != null) {
			// Must set preview at begin,then set others
			camera.setPreviewCallback(null);
			camera.stopPreview();
			camera.release();
			camera = null;
		}

	}

	public class StartClickListener implements OnClickListener {

		@Override
		public void onClick(View v) {
			// TODO Auto-generated method stub
			stopPreview();
			startRecord();
		}
	}

	public class StopClickListener implements OnClickListener {

		@Override
		public void onClick(View v) {
			// TODO Auto-generated method stub
			stopRecord();
		}

	}
}
