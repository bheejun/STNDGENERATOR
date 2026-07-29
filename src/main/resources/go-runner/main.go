package main

import (
	"database/sql"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"

	_ "github.com/go-sql-driver/mysql"
)

//go:embed payload.sql config.json
var payload embed.FS

type Config struct {
	System string `json:"system"`
	Mode string `json:"mode"`
	Host string `json:"host"`
	Port int `json:"port"`
	Database string `json:"database"`
	User string `json:"user"`
	Password string `json:"password"`
	VersionFile string `json:"versionFile"`
}

func main() {
	exe, _ := os.Executable()
	logPath := filepath.Join(filepath.Dir(exe), "wdq_"+strings.ToLower(loadMode())+"_result.log")
	file, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err == nil { defer file.Close(); log.SetOutput(file) }
	if err := run(); err != nil { log.Printf("ERROR: %v", err); if hasArg("--check") { fmt.Fprintln(os.Stderr,err) } else { message("WDQ SQL 실행 실패", err.Error()+"\n\n로그: "+logPath, 0x10) }; os.Exit(1) }
	log.Printf("SUCCESS")
	if hasArg("--check") { fmt.Println("CHECK OK"); return }
	message("WDQ SQL 실행 완료", "정상적으로 완료되었습니다.\n\n로그: "+logPath, 0x40)
}

func run() error {
	cfg, err := loadConfig(); if err != nil { return err }
	log.Printf("START system=%s mode=%s target=%s:%d/%s", cfg.System,cfg.Mode,cfg.Host,cfg.Port,cfg.Database)
	if err := checkVersion(cfg.VersionFile); err != nil { return err }
	dsn := fmt.Sprintf("%s:%s@tcp(%s:%d)/%s?charset=utf8mb4&multiStatements=true&parseTime=true&timeout=5s&readTimeout=5m&writeTimeout=5m",
		cfg.User,url.QueryEscape(cfg.Password),cfg.Host,cfg.Port,cfg.Database)
	db, err := sql.Open("mysql",dsn); if err != nil { return err }; defer db.Close()
	db.SetConnMaxLifetime(time.Minute); db.SetMaxOpenConns(1)
	if err=db.Ping(); err != nil { return fmt.Errorf("MariaDB 연결 실패: %w",err) }
	if hasArg("--check") { log.Printf("CHECK OK"); return nil }
	script, err := payload.ReadFile("payload.sql"); if err != nil { return err }
	if len(strings.TrimSpace(string(script)))==0 { return errors.New("내장 SQL이 비어 있습니다") }
	result, err := db.Exec(string(script)); if err != nil { return fmt.Errorf("SQL 실행 실패: %w",err) }
	if affected,e := result.RowsAffected(); e==nil { log.Printf("ROWS_AFFECTED=%d",affected) }
	return nil
}

func loadConfig()(Config,error){var c Config;b,e:=payload.ReadFile("config.json");if e!=nil{return c,e};e=json.Unmarshal(b,&c);return c,e}
func loadMode()string{c,e:=loadConfig();if e!=nil{return "unknown"};return c.Mode}
func hasArg(want string)bool{for _,a:=range os.Args[1:]{if a==want{return true}};return false}
func checkVersion(path string)error{b,e:=os.ReadFile(path);if e!=nil{return fmt.Errorf("WDQ 버전 파일 확인 실패(%s): %w",path,e)};s:=string(b);if !strings.Contains(s,"9.0")&&!strings.Contains(s,"9.1")&&!strings.Contains(s,"9.2"){return errors.New("WDQ 9.0, 9.1, 9.2 전용 실행 파일입니다")};return nil}
func message(title,text string,flags uintptr){u:=syscall.NewLazyDLL("user32.dll");p:=u.NewProc("MessageBoxW");t,_:=syscall.UTF16PtrFromString(text);h,_:=syscall.UTF16PtrFromString(title);p.Call(0,uintptr(unsafe.Pointer(t)),uintptr(unsafe.Pointer(h)),flags)}
